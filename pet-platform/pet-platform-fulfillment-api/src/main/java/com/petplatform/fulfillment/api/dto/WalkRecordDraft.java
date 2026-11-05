package com.petplatform.fulfillment.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 遛狗履约记录（{@code ful_walk_record}）的写入入参（ADR-048 §6）。
 *
 * <p>与 {@link BathRecordDraft} 的差别就是两张表的差别：遛狗表没有 {@code store_service_id}，
 * 多 {@code duration_min}（NOT NULL）与里程/轨迹/便便次数（都可空）。
 * {@code distanceM}/{@code trackSummary}/{@code poopCount} 本轮<b>不填</b>——
 * 门店 UI 里没有产生它们的入口，硬塞 0 就是把"没记录"伪装成"记录了零"。
 *
 * @param durationMin 必填：本次服务时长，由调用方从预约快照或实际起止时间算出
 */
@Data
@Builder
public class WalkRecordDraft {

    private Long appointmentId;
    private Long petId;
    private Long employeeId;
    private String executorRole;
    private boolean byManager;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer durationMin;
    private String remark;
}
