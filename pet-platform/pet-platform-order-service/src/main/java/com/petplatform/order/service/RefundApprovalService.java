package com.petplatform.order.service;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.order.api.OrderErrorCode;
import com.petplatform.order.api.RefundApprovalApi;
import com.petplatform.order.api.dto.RefundView;
import com.petplatform.order.api.event.RefundDecidedEvent;
import com.petplatform.order.mapper.OrderMapper;
import com.petplatform.order.mapper.RefundMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 退款审批（ADR-037 §4，执行顺序按 ADR-043 §4 纠正为四步）。
 *
 * <p>审批通过走的是 <b>APPLYING→APPROVED→[退款执行]→SUCCESS→订单 REFUNDED</b> 这条链，
 * 每步都是 status 条件更新；任一步 0 行或执行抛错即整笔回滚。
 * 这样"预约被取消""订单变 REFUNDED""REFUND_SUCCESS 消息"三件事在结构上就只能发生在
 * 退款执行成功之后——不是靠文案，靠的是写序。
 *
 * <p>拒绝走 APPLYING→REJECTED + 订单回 PAID；预约恢复原态由 app 装配根消费事件完成。
 * 预约侧的一切都不写在 order 里：矩阵上 order 不主动驱动 appointment（第二阶段 §4.2 事件链单向）。
 *
 * <p>审批人身份写 {@code approve_role}（角色快照，ADR-001-B 分级审计）+ {@code approver_id=userId}。
 * 拒绝理由 {@code reject_reason} 必填落库（V17，ADR-037 §5 的"本期不记录"挂账就此核销）。
 */
@Slf4j
@Service
public class RefundApprovalService implements RefundApprovalApi {

    private final RefundMapper refundMapper;
    private final OrderMapper orderMapper;
    private final RefundExecutor refundExecutor;
    private final ApplicationEventPublisher events;

    public RefundApprovalService(RefundMapper refundMapper, OrderMapper orderMapper,
                                 RefundExecutor refundExecutor, ApplicationEventPublisher events) {
        this.refundMapper = refundMapper;
        this.orderMapper = orderMapper;
        this.refundExecutor = refundExecutor;
        this.events = events;
    }

    @Override
    @Transactional
    public RefundView approve(Long refundId, Approver approver, long managerLimitFen) {
        RefundView refund = requireApprovable(refundId, approver, managerLimitFen);
        return approveAndExecute(refund, roleOf(approver), approver.userId());
    }

    @Override
    @Transactional
    public RefundView autoApprove(Long refundId) {
        if (refundId == null) {
            return null;
        }
        RefundView refund = refundMapper.selectViewById(refundId);
        // 到点时已被人工批/拒（或第一跳 CAS 落后于并发的人工处理）= 常态，静默跳过。
        if (refund == null || !"APPLYING".equals(refund.getStatus())) {
            return null;
        }
        return approveAndExecute(refund, "SYSTEM", null);
    }

    /**
     * 通过后的共用执行链（ADR-043 §4 第一步之后的全部写序）：
     * APPROVED→退款执行→SUCCESS→订单 REFUNDED→事件（预约落定由 app 装配根同事务完成）。
     * 人工审批与超时自动退款走同一条链，保证"消息不早于钱"只有一份机制保证。
     */
    private RefundView approveAndExecute(RefundView refund, String approveRole, Long approverId) {
        // 第一步：审批通过。此时钱还没动，所以订单/预约一律不变（ADR-043 §4）。
        if (refundMapper.updateApproved(refund.getId(), approveRole, approverId) == 0) {
            throw new BusinessException(OrderErrorCode.REFUND_NOT_APPROVABLE);
        }
        // 第二步：退款执行（当前为 Mock，抛异常即整笔回滚，不会留下"已批未执行"的死账）。
        RefundView approved = refundMapper.selectViewById(refund.getId());
        refundExecutor.execute(approved == null ? refund : approved);
        // 第三步：执行成功才允许记 SUCCESS。
        if (refundMapper.updateExecutedSuccess(refund.getId()) == 0) {
            throw new BusinessException(OrderErrorCode.REFUND_NOT_APPROVABLE);
        }
        // 第四步：订单 PAID/REFUNDING→REFUNDED。0 行=订单侧漂移，整笔回滚。
        if (orderMapper.updateRefunded(refund.getOrderId()) == 0) {
            throw new BusinessException(OrderErrorCode.ORDER_STATE_CONFLICT);
        }
        log.info("退款审批通过并执行成功 refundId={} orderId={} appointmentId={} amount={} by={} role={}",
                refund.getId(), refund.getOrderId(), refund.getAppointmentId(), refund.getAmount(),
                approverId, approveRole);
        // 预约落 CANCELLED（含释放占用、撤销托管住单）由 app 装配根消费本事件完成——
        // 顺序上已在"钱退成功"之后，这正是本次纠正的落点。
        events.publishEvent(new RefundDecidedEvent(refund.getId(), refund.getOrderId(),
                refund.getAppointmentId(), refund.getStoreId(), refund.getUserId(), refund.getAmount(),
                "SUCCESS", LocalDateTime.now()));
        return refundMapper.selectViewById(refund.getId());
    }

    @Override
    @Transactional
    public RefundView reject(Long refundId, Approver approver, long managerLimitFen, String rejectReason) {
        requireRejectReason(rejectReason);
        RefundView refund = requireApprovable(refundId, approver, managerLimitFen);
        if (refundMapper.updateRejected(refundId, roleOf(approver), approver.userId(), rejectReason) == 0) {
            throw new BusinessException(OrderErrorCode.REFUND_NOT_APPROVABLE);
        }
        if (orderMapper.updateRefundRejected(refund.getOrderId()) == 0) {
            throw new BusinessException(OrderErrorCode.ORDER_STATE_CONFLICT);
        }
        log.info("退款审批拒绝 refundId={} orderId={} appointmentId={} by={} role={}", refundId,
                refund.getOrderId(), refund.getAppointmentId(), approver.userId(), roleOf(approver));
        // 预约恢复可服务态同样由 app 装配根消费事件完成（order 不反向写 appointment 表）。
        events.publishEvent(new RefundDecidedEvent(refund.getId(), refund.getOrderId(),
                refund.getAppointmentId(), refund.getStoreId(), refund.getUserId(), refund.getAmount(),
                "REJECTED", LocalDateTime.now()));
        return refundMapper.selectViewById(refundId);
    }

    /**
     * 审批前置判定。店长额外受两道闸：只能处理本店退款单（跨店统一 10004，不回显存在性）、
     * 金额不得超 sys_config 507 上限。<b>拒绝也受同一额度闸</b>——否则店长可以替 ADMIN 把
     * 大额退款直接拒掉，等于把"只有 ADMIN 能动大额"这条线反向击穿。
     */
    private RefundView requireApprovable(Long refundId, Approver approver, long managerLimitFen) {
        RefundView refund = refundId == null ? null : refundMapper.selectViewById(refundId);
        if (refund == null || !"APPLYING".equals(refund.getStatus())) {
            throw new BusinessException(OrderErrorCode.REFUND_NOT_APPROVABLE);
        }
        if (approver.admin()) {
            return refund;
        }
        if (refund.getStoreId() == null || !refund.getStoreId().equals(approver.storeId())) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        if (refund.getAmount() != null && refund.getAmount() > managerLimitFen) {
            throw new BusinessException(OrderErrorCode.REFUND_LIMIT_FOR_MANAGER);
        }
        return refund;
    }

    private static String roleOf(Approver approver) {
        return approver.admin() ? "ADMIN" : "STORE_MANAGER";
    }

    /** 拒绝理由必填 1~200 字：空=顾客看不到任何原因（C4 欠账重新出现），超长=ord_refund 列宽截断风险。 */
    private static void requireRejectReason(String rejectReason) {
        if (rejectReason == null || rejectReason.isBlank()) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "拒绝理由不能为空");
        }
        if (rejectReason.length() > 200) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "拒绝理由不能超过 200 字");
        }
    }
}
