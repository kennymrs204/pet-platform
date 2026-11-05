package com.petplatform.appointment.api.event;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 执行人指派事件（ADR-052）：店长/前台经 {@code assign-employee} 把某人派上某单（含改派），
 * CAS 落库成功（changed=true）后 AFTER_COMMIT 发布。订阅方（执行人"来活了"站内信）须
 * {@code @TransactionalEventListener(AFTER_COMMIT)}——指派回滚则消息不存在。
 *
 * <p>字段取自指派事务内的 {@code apt} 实体与上架快照，订阅方零回查。
 * {@code employeeId} 是**新执行人**；{@code oldEmployeeId} 是改派前的原执行人
 * （首次指派为 null）——ADR-053 起旧执行人也收一条"任务已调整"（被换下来的人该知道），
 * 新旧是同一人时旧通知不发。
 */
public record AppointmentAssignedEvent(
        Long appointmentId,
        Long storeId,
        Long employeeId,
        Long oldEmployeeId,
        String serviceName,
        LocalDate serviceDate,
        LocalTime startTime,
        LocalDateTime occurredAt
) {
}
