package com.petplatform.boarding.api.event;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 异常护理事件（ADR-015 §5）：abnormalFlag=true 记录落库后事务内发布。
 * 订阅方须 @TransactionalEventListener(AFTER_COMMIT)；notification 段接入前无消费者，
 * 禁止 boarding 直写 ntf_notification。异常记录不改入住单状态、不阻塞离店。
 */
public record BoardingCareAbnormalEvent(
        Long boardingStayId,
        Long petId,
        Long storeId,
        LocalDate careDate,
        LocalDateTime occurredAt
) {
}
