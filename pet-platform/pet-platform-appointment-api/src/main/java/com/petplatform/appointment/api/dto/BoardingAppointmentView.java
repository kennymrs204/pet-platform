package com.petplatform.appointment.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

/**
 * 托管视角预约快照（ADR-015，boarding 校验用；表 Owner=appointment，经 api 出）。
 * checkinDate 非空即 MULTI_DAY 托管预约（apt_appointment.boarding_checkin_date）。
 */
@Data
@Builder
public class BoardingAppointmentView {

    private Long appointmentId;
    private Long storeId;
    private Long petId;
    /** PENDING_PAYMENT/CONFIRMED/WAIT_SERVICE/SERVING/COMPLETED/CANCELLED */
    private String status;
    private LocalDate checkinDate;
    private LocalDate checkoutDate;
    private String serviceNameSnapshot;
}
