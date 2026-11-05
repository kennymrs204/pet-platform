package com.petplatform.appointment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 创建预约（第八阶段 §3.1，ADR-016；requestId 幂等 W 裁决：Redis SETNX TTL 5min，重复 10005）。
 * INSTANT：serviceDate+startTime 必填；MULTI_DAY：boarding 入住/离店日期必填且 checkout>checkin，
 * 两类字段不得混传（Service 层按 fulfillmentType 严格分派）。
 */
@Data
public class AppointmentCreateRequest {

    @NotBlank(message = "requestId必填")
    @Size(max = 64, message = "requestId过长")
    private String requestId;

    @NotNull(message = "宠物必填")
    private Long petId;

    @NotNull(message = "门店服务必填")
    private Long storeServiceId;

    /** 可空；非空须 allow_user_pick_employee=true（C7） */
    private Long employeeId;

    private LocalDate serviceDate;

    /** 30min 对齐（:00/:30），Service 校验 */
    private LocalTime startTime;

    private LocalDate boardingCheckinDate;

    private LocalDate boardingCheckoutDate;

    @Size(max = 500, message = "备注不超过500字")
    private String remark;
}
