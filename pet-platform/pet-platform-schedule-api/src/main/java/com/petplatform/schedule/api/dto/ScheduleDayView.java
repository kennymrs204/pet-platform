package com.petplatform.schedule.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/** 排班日视图（sch_schedule 一行=一人一天一班，ADR-005-1）。 */
@Data
@Builder
public class ScheduleDayView {

    private Long id;
    private Long employeeId;
    private LocalDate workDate;
    private LocalTime startTime;
    private LocalTime endTime;
    /** WORKING/LEAVE */
    private String status;
    /** NONE/APPLYING/APPROVED */
    private String leaveStatus;
}
