package com.petplatform.schedule.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** 预约的笼位（DAY/slot 99）占用行（ADR-015）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OccupancyView {

    private Long resourceId;
    private LocalDate workDate;
}
