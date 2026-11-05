package com.petplatform.order.api;

import com.petplatform.order.api.dto.RefundView;

/**
 * 退款审批（ADR-037 §4 三道闸 + ADR-043 §4 四步写序）。
 * 调用方=app 装配层（只有它能同时看到 storeCtx、角色码与 sys_config 507）。
 *
 * <p><b>审批与执行是两件事</b>：通过 = {@code APPLYING→APPROVED}，随后经
 * {@code RefundExecutor}（当前 {@code MockRefundExecutor}，报成功但不碰渠道）
 * 才记 {@code APPROVED→SUCCESS}，再往后才是订单 {@code REFUNDING→REFUNDED} 与
 * 预约落 {@code CANCELLED}（由 app 层监听退款事件完成）。执行失败=抛异常=整笔回滚，
 * 库里不会留下"批了却没退成"的悬挂单。
 *
 * <p><b>刻意不写 {@code pay_refund_txn}</b>——那是渠道流水表（payment 模块所有），
 * 塞一条假流水会污染日对账口径（ord_recon_diff 的 TXN_MISSING/STATUS_DIVERGE 会被长期干扰）。
 * 接真实渠道时启用 {@code EXECUTING} 与回执幂等，见 ADR-037 §6 与 ADR-043 §8 待办。
 */
public interface RefundApprovalApi {

    /**
     * 通过退款申请。
     *
     * @param managerLimitFen 店长可批上限（分，sys_config 507）；{@code approver.admin()} 时忽略
     * @throws com.petplatform.common.error.BusinessException 60005 状态漂移/不可审批；
     *                                                         60006 超出店长可批上限；10004 非本店退款单
     */
    RefundView approve(Long refundId, Approver approver, long managerLimitFen);

    /**
     * 拒绝退款申请：{@code APPLYING→REJECTED}，订单 {@code REFUNDING→PAID}
     * （第六阶段 §7"回原状态"），并且<b>预约由 {@code CANCEL_REQUESTED} 恢复成 {@code CONFIRMED}</b>
     * ——占用从未释放，所以恢复后这条预约照样可服务（ADR-043 §3；旧口径"预约保持 CANCELLED 不可逆"已作废）。
     * 拒绝理由 {@code rejectReason} 必填（1~200 字，V17 起落 {@code ord_refund.reject_reason}），
     * 顾客在 C 端退款进度卡可见。
     *
     * <p>限额闸对拒绝**同样生效**：第六阶段 §7 的"超额仅 ADMIN"读的是"超额退款单只能由 ADMIN 处置"，
     * 否则店长可以替 ADMIN 把大额申请一口回绝，把这条线从反方向击穿。
     */
    RefundView reject(Long refundId, Approver approver, long managerLimitFen, String rejectReason);

    /**
     * 超时自动退款（本单设计）：定时任务对"到点未被人工处理"的 APPLYING 单兜底放行。
     * 审批人记 {@code approve_role='SYSTEM'}、{@code approver_id=null}；跳过"限本店/限额"两道闸
     * （额度闸管的是人，不是系统兜底），但保留 {@code APPLYING} 判定——期间被人工批/拒过的单
     * 返回 {@code null} 静默跳过，不抛错（对定时任务，"已被处理"是常态不是异常）。
     * 其余执行写序与 {@link #approve} 完全一致（APPROVED→执行→SUCCESS→订单 REFUNDED→事件）。
     */
    RefundView autoApprove(Long refundId);

    /**
     * 审批人上下文。
     *
     * @param storeId 当前工作门店（ADMIN 为总部店 1，不参与越权判定）
     * @param admin   是否平台管理员：跳过"限本店"与"限额"两道闸
     */
    record Approver(Long userId, Long employeeId, Long storeId, boolean admin) {
    }
}
