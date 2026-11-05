package com.petplatform.appointment.service;

import com.petplatform.appointment.api.AppointmentCommandApi;
import com.petplatform.appointment.api.AppointmentErrorCode;
import com.petplatform.appointment.api.dto.AppointmentCreateRequest;
import com.petplatform.appointment.api.dto.AppointmentCreatedView;
import com.petplatform.appointment.api.event.AppointmentCancelledEvent;
import com.petplatform.appointment.api.event.AppointmentPaidEvent;
import com.petplatform.appointment.domain.AppointmentEntity;
import com.petplatform.appointment.mapper.AppointmentCommandMapper;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.schedule.api.ResourceOccupancyApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 预约状态命令（ADR-016 §2/§4）：条件更新先到先得，重放静默、非法迁移 50003。
 * confirm 由 app 事件桥（订单支付链）调用；cancelTimeout 由超时编排（app/job）调用；
 * 取消的"申请/落定/恢复"三态由 ADR-043 定义——申请在 {@code AppointmentCancelTxService}，
 * 落定与恢复是本类的两个方法，由 app 装配根在退款审批事务内回调。
 */
@Slf4j
@Service
public class AppointmentCommandService implements AppointmentCommandApi {

    /** 已确认及其之后的状态：支付成功事件重放时静默（CANCEL_REQUESTED 也是"已付过"的后态）。 */
    private static final Set<String> AFTER_CONFIRM = Set.of(
            "CONFIRMED", "CANCEL_REQUESTED", "WAIT_SERVICE", "SERVING", "COMPLETED");

    private final AppointmentCommandMapper mapper;
    private final ResourceOccupancyApi occupancyApi;
    private final ApplicationEventPublisher events;
    private final AppointmentCreateService createService;

    public AppointmentCommandService(AppointmentCommandMapper mapper, ResourceOccupancyApi occupancyApi,
                                     ApplicationEventPublisher events, AppointmentCreateService createService) {
        this.mapper = mapper;
        this.occupancyApi = occupancyApi;
        this.events = events;
        this.createService = createService;
    }

    /**
     * B 端代客建单（app 装配层调用）：纯委托建单链（锁/幂等/事务段在 {@link AppointmentCreateService}
     * 内部成形），本类不包事务——包一层会改变"锁外快速失败 + 事务段"的既有语义。
     */
    @Override
    public AppointmentCreatedView createForCustomer(Long storeId, Long customerUserId, AppointmentCreateRequest req) {
        return createService.createForCustomer(storeId, customerUserId, req);
    }

    @Override
    @Transactional
    public void confirm(Long appointmentId) {
        AppointmentEntity apt = mapper.selectById(appointmentId);
        if (apt == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        if (!"PENDING_PAYMENT".equals(apt.getStatus())) {
            if (AFTER_CONFIRM.contains(apt.getStatus())) {
                return; // 事件重放：幂等静默（不再发 AppointmentPaidEvent，防入住单重复驱动）
            }
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION, "预约已取消，支付结果转人工处理");
        }
        if (mapper.updateConfirm(appointmentId) == 0) {
            return; // 并发竞争失败方：对方已推进
        }
        events.publishEvent(new AppointmentPaidEvent(appointmentId, apt.getOrderId(), apt.getStoreId(),
                apt.getPetId(), apt.getBoardingCheckinDate() != null, LocalDateTime.now()));
    }

    @Override
    @Transactional
    public void cancelTimeout(Long appointmentId) {
        if (mapper.updateCancelTimeout(appointmentId) == 0) {
            // 0 行=状态已被他链推进：调用方（超时编排）整体回滚，防"单已取消约未取消"半截态
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION, "预约状态已变更，取消中止");
        }
    }

    /**
     * 取消真正生效的地方（ADR-043 §4）：钱退成功了，才把预约落到 CANCELLED、释放台位/笼位、
     * 通知托管侧撤销 EXPECTED 住单。三步与本事务内的退款写同生同死。
     */
    @Override
    @Transactional
    public void landCancellationAfterRefund(Long appointmentId) {
        AppointmentEntity apt = appointmentId == null ? null : mapper.selectById(appointmentId);
        if (apt == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        if (mapper.updateCancelLand(appointmentId) == 0) {
            // 0 行且已是 CANCELLED=旧链存量（ADR-043 前的退款单）重放落定：预约/占用早已终态，
            // 放行退款主事务（占用幂等 DELETE 自愈），但不再重发取消事件（下游撤销住单等只该发生一次）。
            if ("CANCELLED".equals(mapper.selectById(appointmentId).getStatus())) {
                occupancyApi.releaseAll(appointmentId);
                log.info("退款成功，预约已终态，幂等放行落定 appointmentId={}", appointmentId);
                return;
            }
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION,
                    "预约已不在取消审核中，退款落定中止");
        }
        occupancyApi.releaseAll(appointmentId);
        events.publishEvent(new AppointmentCancelledEvent(appointmentId,
                apt.getBoardingCheckinDate() != null, LocalDateTime.now()));
        log.info("退款成功，取消落定 appointmentId={} multiDay={}", appointmentId, apt.getBoardingCheckinDate() != null);
    }

    @Override
    @Transactional
    public void restoreAfterRefundReject(Long appointmentId) {
        if (appointmentId == null || mapper.updateCancelRestore(appointmentId) == 0) {
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION,
                    "预约已不在取消审核中，拒绝回滚中止");
        }
        log.info("退款被拒，预约恢复可服务 appointmentId={}", appointmentId);
    }
}
