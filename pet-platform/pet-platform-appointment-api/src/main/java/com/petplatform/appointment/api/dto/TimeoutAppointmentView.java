package com.petplatform.appointment.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 超时扫描行（app/OrderTimeoutJob 编排输入，ADR-016 §4）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimeoutAppointmentView {

    private Long appointmentId;
    private Long orderId;
    private Long storeId;
    private Long petId;
    /** MULTI_DAY（boarding_checkin_date 非空） */
    private boolean multiDay;
}
