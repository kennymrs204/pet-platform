package com.petplatform.boarding.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 入住单详情（B 端内部视角，可含预约/笼位/员工 id；C 端另走脱敏视图）。 */
@Data
@Builder
public class BoardingStayDetailView {

    private Long id;
    private Long appointmentId;
    private Long petId;
    private Long storeId;
    /** EXPECTED/CHECKED_IN/DAILY_CARE/CHECKED_OUT/ABNORMAL */
    private String status;
    private LocalDate checkinDate;
    private LocalDate checkoutDate;
    private LocalDateTime actualCheckinAt;
    private LocalDateTime actualCheckoutAt;
    private Long kennelResourceId;
    private Long caretakerId;
    private String specialInstructions;
}
