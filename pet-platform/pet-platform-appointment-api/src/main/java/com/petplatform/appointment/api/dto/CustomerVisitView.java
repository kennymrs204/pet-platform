package com.petplatform.appointment.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 客户到店史单行（ADR-017 §5 /customers/{userId}/appointments 与详情 visits 块共用）。
 * 快照字段直出（service_name_snapshot / price_snapshot），不回查 catalog；
 * employeeId 由调用方经 EmployeeQueryApi 回显姓名（表 Owner 边界）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerVisitView {

    private Long appointmentId;
    private LocalDate serviceDate;
    private String serviceName;
    /** PENDING_PAYMENT/CONFIRMED/WAIT_SERVICE/SERVING/COMPLETED/CANCELLED */
    private String status;
    /** 分（Long 经全局 Jackson 字符串化） */
    private Long priceSnapshot;
    private Long employeeId;
    private Long petId;
}
