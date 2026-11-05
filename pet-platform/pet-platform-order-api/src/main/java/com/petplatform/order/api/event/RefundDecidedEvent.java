package com.petplatform.order.api.event;

import java.time.LocalDateTime;

/**
 * 退款审批结果事件（ADR-037 §5）。发布方=order（approve/reject 走完本域写之后），
 * 消费者=app 装配层的两个同步监听（@EventListener 同事务）：
 * {@code RefundSettlementListener} 落定/恢复预约，{@code RefundNotificationListener} 发消息。
 *
 * <p>{@code outcome} 取退款单审批后的**最终状态**：{@code SUCCESS} 或 {@code REJECTED}。
 * <b>SUCCESS 只有在退款执行成功之后才会发出来</b>（ADR-043 §4）——
 * "审核通过"是 APPROVED，它不是本事件的一种 outcome，
 * 这样 REFUND_SUCCESS 消息就不可能早于钱真的退掉。
 *
 * <p>{@code appointmentId} 由 {@code RefundView} 联 ord_order 带出（发布方手上已有），
 * 供装配根直接定位要落定/恢复的预约，不必再回查一次退款单。
 */
public record RefundDecidedEvent(
        Long refundId,
        Long orderId,
        Long appointmentId,
        Long storeId,
        Long userId,
        Long amountFen,
        String outcome,
        LocalDateTime occurredAt
) {
}
