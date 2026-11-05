package com.petplatform.boarding.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * C 端托管日报视图（ADR-015 §4）：仅宠物主人需要的字段。
 * 不暴露 appointmentId/kennelResourceId/caretakerId/storeId 等内部 id。
 */
@Data
@Builder
public class CustomerStayView {

    private Long id;
    private Long petId;
    private String status;
    private LocalDate checkinDate;
    private LocalDate checkoutDate;
    private LocalDateTime actualCheckinAt;
    private LocalDateTime actualCheckoutAt;
    private String specialInstructions;
    /** 护理员展示名（仅姓名；无护理员为 null） */
    private String caretakerName;
}
