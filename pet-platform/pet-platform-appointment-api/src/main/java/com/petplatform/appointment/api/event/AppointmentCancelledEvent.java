package com.petplatform.appointment.api.event;

import java.time.LocalDateTime;

/**
 * 预约取消事件（ADR-023 §3；发布方 appointment，消费者=app 装配层桥接
 * BoardingStayProvisioningApi.cancelExpected——appointment→boarding 非矩阵边，
 * 与 AppointmentPaidEvent 同构：消费者只能在装配根，同步 @EventListener 同事务）。
 * 仅 MULTI_DAY 触发入住单撤销；EXPECTED 逻辑删幂等，重放安全。
 */
public record AppointmentCancelledEvent(
        Long appointmentId,
        boolean multiDay,
        LocalDateTime occurredAt
) {
}
