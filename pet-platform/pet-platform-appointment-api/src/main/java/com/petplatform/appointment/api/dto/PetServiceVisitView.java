package com.petplatform.appointment.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 宠物当前门店服务历史的一行预约主数据（ADR-014 后续增量）。
 * 只由 appointment 模块出数；履约详情、托管详情和员工姓名由 app 装配层按
 * appointmentId 批量补充，本 DTO 不跨模块引用 fulfillment/boarding 类型。
 */
@Data
@Builder
public class PetServiceVisitView {

    private Long appointmentId;
    private String appointmentNo;
    private Long petId;
    private LocalDate serviceDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String serviceName;
    /** PENDING_PAYMENT/CONFIRMED/CANCEL_REQUESTED/WAIT_SERVICE/SERVING/COMPLETED/CANCELLED */
    private String status;
    /** 分（Long 经全局 Jackson 字符串化） */
    private Long priceSnapshot;
    private Long employeeId;
    private LocalDate boardingCheckinDate;
    private LocalDate boardingCheckoutDate;
    private LocalDateTime actualEndAt;
}
