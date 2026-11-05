package com.petplatform.appointment.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/** 门店预约列表行（快照字段直出，不回查 catalog；宠物/员工姓名由 web 层经对方模块 api 回填）。 */
@Data
@Builder
public class AppointmentStoreView {

    private Long id;
    private String appointmentNo;
    private LocalDate serviceDate;
    private LocalTime startTime;
    private LocalTime endTime;
    /** PENDING_PAYMENT/CONFIRMED/WAIT_SERVICE/SERVING/COMPLETED/CANCELLED */
    private String status;
    private String serviceName;
    /** 分（Long 经全局 Jackson 字符串化） */
    private Long priceSnapshot;
    /** CUSTOMER/RECEPTIONIST */
    private String source;
    private Long employeeId;
    private Long petId;
    /** 上架服务 id；洗护看板据此取服务码（PET_BATH/PET_GROOMING）与所需台位类型（ADR-038） */
    private Long storeServiceId;
}
