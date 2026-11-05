package com.petplatform.payment.api.event;

import java.time.LocalDateTime;

/**
 * 支付成功事件（ADR-016 §2；发布方 payment，消费者=app 装配层桥接 OrderCommandApi.markPaid，
 * 同步同事务，链路"流水 SUCCESS + 订单 PAID + 预约 CONFIRMED + 入住单 EXPECTED"原子）。
 */
public record PaymentSucceededEvent(
        Long orderId,
        Long amount,
        String channel,
        String channelTxnNo,
        LocalDateTime occurredAt
) {
}
