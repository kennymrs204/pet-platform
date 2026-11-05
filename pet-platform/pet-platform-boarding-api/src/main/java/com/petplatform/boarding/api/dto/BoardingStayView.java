package com.petplatform.boarding.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 托管入住单视图（宠物/护理员姓名由 web 层经对方模块 api 回填）。 */
@Data
@Builder
public class BoardingStayView {

    private Long id;
    private Long appointmentId;
    private Long petId;
    private LocalDate checkinDate;
    private LocalDate checkoutDate;
    private LocalDateTime actualCheckinAt;
    /** EXPECTED/CHECKED_IN/DAILY_CARE/CHECKED_OUT/ABNORMAL */
    private String status;
    private Long caretakerId;
}
