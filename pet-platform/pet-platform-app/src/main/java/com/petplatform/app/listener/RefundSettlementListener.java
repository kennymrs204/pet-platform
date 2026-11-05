package com.petplatform.app.listener;

import com.petplatform.appointment.api.AppointmentCommandApi;
import com.petplatform.order.api.event.RefundDecidedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 退款结果 → 预约落定（ADR-043 §3/§4）。
 *
 * <p><b>为什么落在 app</b>：这条边是 order 事件 → appointment 写，两个域都不该反向依赖对方实现
 * （第二阶段 §4.2 事件链单向 payment→order→appointment），能同时看到两边的只有组合根
 * （{@code PaymentChainListener}／{@code AppointmentCancelListener} 同构先例）。
 * 同步 @EventListener 加入审批事务，所以"退款 SUCCESS + 订单 REFUNDED + 预约落定 + 占用释放 +
 * 住单撤销"是同一笔本地事务，全成或全不成。
 *
 * <p><b>顺序即业务</b>：本监听只在 {@code outcome=SUCCESS} 时把预约落到 CANCELLED——
 * 而 SUCCESS 是退款执行成功之后才写的（见 {@code RefundApprovalService#approve}），
 * 于是"订单最终取消晚于退款成功"由写序保证，不靠前端判断，也不靠文案。
 *
 * <p>0 行/状态不符时 appointment 侧抛 50003，整笔审批回滚：宁可让店长重试一次，
 * 也不要"钱退了、预约还在审核中"这种账实不符。
 */
@Slf4j
@Component
public class RefundSettlementListener {

    private final AppointmentCommandApi appointmentCommandApi;

    public RefundSettlementListener(AppointmentCommandApi appointmentCommandApi) {
        this.appointmentCommandApi = appointmentCommandApi;
    }

    @EventListener
    public void onDecided(RefundDecidedEvent event) {
        if (event.appointmentId() == null) {
            // 退款单联不出预约（历史脏数据）：不能凭空造一条取消，交给审批事务报错更安全
            throw new IllegalStateException("退款事件缺 appointmentId，refundId=" + event.refundId());
        }
        if ("SUCCESS".equals(event.outcome())) {
            appointmentCommandApi.landCancellationAfterRefund(event.appointmentId());
        } else {
            appointmentCommandApi.restoreAfterRefundReject(event.appointmentId());
        }
    }
}
