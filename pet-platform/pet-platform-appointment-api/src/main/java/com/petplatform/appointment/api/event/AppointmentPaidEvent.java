package com.petplatform.appointment.api.event;

import java.time.LocalDateTime;

/**
 * 预约支付成功事件（ADR-016 §2；发布方 appointment，消费者=app 装配层桥接
 * BoardingStayProvisioningApi——appointment→boarding 非矩阵边，故消费者只能在装配根）。
 * 仅 MULTI_DAY 触发入住单创建；uk_appointment 幂等，重放安全。
 */
public record AppointmentPaidEvent(
        Long appointmentId,
        Long orderId,
        Long storeId,
        Long petId,
        boolean multiDay,
        LocalDateTime occurredAt
) {
}
