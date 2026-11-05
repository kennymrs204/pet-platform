package com.petplatform.order.api.event;

import java.time.LocalDateTime;

/**
 * 订单支付成功事件（ADR-016 §2；发布方 order，消费者=app 装配层桥接到 appointment-api）。
 * 同步 @EventListener 加入发布事务（ADR-006-2 回调链同事务）；重放安全由下游条件更新保证。
 */
public record OrderPaidEvent(
        Long orderId,
        Long appointmentId,
        Long userId,
        LocalDateTime occurredAt
) {
}
