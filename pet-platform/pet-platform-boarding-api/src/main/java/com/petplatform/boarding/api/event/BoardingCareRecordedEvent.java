package com.petplatform.boarding.api.event;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 护理记录落库事件（ADR-015 §5 补记 2026-10-27，用户拍板 b 案：每条记录都给顾客推站内信）。
 * 每条 DailyCareRecord 插入成功后事务内发布（含异常上报的记录——异常另有 CARE_ABNORMAL 给店端，两者并存）。
 * 订阅方须 @TransactionalEventListener(AFTER_COMMIT)；禁止 boarding 直写 ntf_notification。
 * ownerId 在发布前由 appointmentId 解析（stay 表无 user_id）；解析不到为 null，订阅方静默跳过。
 *
 * @param boardingStayId 住单 id
 * @param appointmentId  来源预约 id（全部住单 NOT NULL，收件人解析依据）
 * @param petId          宠物 id（正文摘要用）
 * @param storeId        门店 id
 * @param ownerId        宠物主人 user_id（顾客收件人；null=无法解析，不推送）
 * @param careDate       护理日期
 * @param abnormal       该条是否异常上报（仅影响文案，不改变推送行为）
 * @param occurredAt     发生时间
 */
public record BoardingCareRecordedEvent(
        Long boardingStayId,
        Long appointmentId,
        Long petId,
        Long storeId,
        Long ownerId,
        LocalDate careDate,
        boolean abnormal,
        LocalDateTime occurredAt
) {
}
