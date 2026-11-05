package com.petplatform.appointment.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** C 端"我的预约"视图（appointment:view:self，仅本人数据；ADR-016）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppointmentSelfView {

    private Long id;
    private String appointmentNo;
    private Long petId;
    private Long storeId;
    private Long storeServiceId;
    private Long employeeId;
    private LocalDate serviceDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private LocalDate boardingCheckinDate;
    private LocalDate boardingCheckoutDate;
    /** PENDING_PAYMENT/CONFIRMED/WAIT_SERVICE/SERVING/COMPLETED/CANCELLED */
    private String status;
    private String serviceNameSnapshot;
    /** 分 */
    private Long priceSnapshot;
    private Integer durationSnapshot;
    private String remark;
    private LocalDateTime expireAt;
    private LocalDateTime createdAt;
}
