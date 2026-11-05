package com.petplatform.schedule.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/** 班次锁视图（ADR-016，lockScheduleForCreate 返回）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleLockView {

    private Long employeeId;
    private LocalDate workDate;
    private LocalTime startTime;
    private LocalTime endTime;
    /** WORKING/LEAVE */
    private String status;
}
