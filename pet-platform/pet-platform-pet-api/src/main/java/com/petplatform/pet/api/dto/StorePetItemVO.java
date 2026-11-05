package com.petplatform.pet.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * B 端宠物列表行（ADR-014，健康最小披露：只给计数/标志/疫苗摘要，不给明细数组）。
 * lastService：fulfillment 服务记录优先，无记录回落最近预约快照；均无 → null。
 * boarding：仅当宠物当前在店（CHECKED_IN/DAILY_CARE）非空。
 */
@Data
@Builder
public class StorePetItemVO {

    private Long id;
    private String name;
    private String species;
    private String breed;
    private String gender;
    private LocalDate birthday;
    private Boolean birthdayApprox;
    private BigDecimal weightKg;
    private String sizeCategory;
    private String avatar;
    /** 头像 presigned GET（ADR-029 §契约4）；存储未开启或归属校验不过 → null，前端回退占位。avatar 仍是裸 key，别当 URL 用 */
    private String avatarUrl;
    private String status;
    private HealthSummaryVO health;
    private LastServiceVO lastService;
    private BoardingBriefVO boarding;

    /** 健康摘要（列表级最小披露）。 */
    @Data
    @Builder
    public static class HealthSummaryVO {
        private int allergyCount;
        private int chronicCount;
        private boolean hasSpecialNotes;
        /** medical 事件同步的接种摘要，可为 null */
        private String vaccineSummary;
    }

    /** 最近服务摘要。 */
    @Data
    @Builder
    public static class LastServiceVO {
        /** SERVICE=履约记录（真实完成），APPOINTMENT=预约快照兜底 */
        private String source;
        private Long appointmentId;
        /** source=SERVICE 时为 BATH/WALK；source=APPOINTMENT 时为服务名快照 */
        private String label;
        /** 完成时间（SERVICE） */
        private LocalDateTime finishedAt;
        /** 服务日期（APPOINTMENT 兜底） */
        private LocalDate serviceDate;
        /** 预约状态（仅 APPOINTMENT 兜底时有值），COMPLETED 视为已完成 */
        private String status;
    }

    /** 当前托管摘要。 */
    @Data
    @Builder
    public static class BoardingBriefVO {
        private Long stayId;
        /** CHECKED_IN/DAILY_CARE */
        private String status;
        private LocalDate checkinDate;
        private LocalDate checkoutDate;
    }
}
