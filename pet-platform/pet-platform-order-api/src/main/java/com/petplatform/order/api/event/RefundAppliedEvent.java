package com.petplatform.order.api.event;

import java.time.LocalDateTime;

/**
 * 退款申请产生事件（ADR-037 §5）。发布方=order（refundOnCancel 落单成功后），
 * 消费者=app 的 RefundNotificationListener（同步 @EventListener 同事务，PaymentChainListener 先例）。
 *
 * <p>用途只有一个：通知门店"有一笔退款待处理"。收件人（该店 STORE_MANAGER 的 userId）
 * 由 app 层查员工名册得到——order 模块不依赖 employee，矩阵零新增边。
 */
public record RefundAppliedEvent(
        Long refundId,
        Long orderId,
        Long appointmentId,
        Long storeId,
        Long userId,
        Long amountFen,
        LocalDateTime occurredAt
) {
}
