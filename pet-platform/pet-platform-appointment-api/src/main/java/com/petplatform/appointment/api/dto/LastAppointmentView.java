package com.petplatform.appointment.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

/** 宠物最近一次预约快照（ADR-014 B 端宠物列表"最近服务"兜底数据源，快照字段不回查 catalog）。 */
@Data
@Builder
public class LastAppointmentView {

    private Long petId;
    private Long appointmentId;
    private String serviceName;
    private LocalDate serviceDate;
    /** PENDING_PAYMENT/CONFIRMED/WAIT_SERVICE/SERVING/COMPLETED/CANCELLED */
    private String status;
}
