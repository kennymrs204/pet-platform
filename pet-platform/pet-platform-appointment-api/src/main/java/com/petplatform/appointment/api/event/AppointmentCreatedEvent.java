package com.petplatform.appointment.api.event;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 预约创建事件（第五阶段 §5.2：AFTER_COMMIT → notification 站内信）。
 * 订阅方须 @TransactionalEventListener(AFTER_COMMIT)。
 *
 * <p>ADR-052 扩展：事件此前只有 (appointmentId, userId, storeId, occurredAt)，通知桥想给
 * "被指定执行人"发消息时既不知道有没有执行人，也编不出文案（服务名/时间都没带）。
 * 现由创建事务把 {@code apt} 上的事实一并放进事件——订阅方不再回查，回查口径分裂比字段多一点更糟。
 * {@code employeeId == null} = 顾客未指定执行人（随机安排），执行人通知自然不发。
 */
public record AppointmentCreatedEvent(
        Long appointmentId,
        Long userId,
        Long storeId,
        Long employeeId,
        String serviceName,
        LocalDate serviceDate,
        LocalTime startTime,
        LocalDateTime occurredAt
) {
}
