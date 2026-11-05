package com.petplatform.appointment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 改期请求（ADR-026，PUT /customer/appointments/{id}/modify，仅 PENDING_PAYMENT）。
 * 白名单：INSTANT 改 serviceDate+startTime（成对，employeeId 可选按 C7 闸）；
 * MULTI_DAY 改 boardingCheckinDate+boardingCheckoutDate（成对）。
 * storeServiceId/petId/remark 不在体内——换服务=取消+重订（ADR-026 §1）。
 */
@Data
public class ModifyAppointmentRequest {

    @NotBlank(message = "requestId必填")
    @Size(max = 64, message = "requestId过长")
    private String requestId;

    private LocalDate serviceDate;
    private LocalTime startTime;
    private Long employeeId;
    private LocalDate boardingCheckinDate;
    private LocalDate boardingCheckoutDate;
}
