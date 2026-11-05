package com.petplatform.schedule.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 排班行摘要（ADR-034）：写侧的回执，也是 app 层控制器做预约预检前必须先拿到的那条行的真相。
 *
 * <p>带 {@code storeId} 是必需的而不是冗余：改/删一条排班前，控制器要知道"这行属于哪家店"才能判跨店
 * （不属本店一律 10004），而预检预约又要按 (员工, 日期) 去问 appointment——两件事都要这两个字段。
 */
@Data
@Builder
public class ScheduleBrief {

    private Long id;
    private Long employeeId;
    private Long storeId;
    private LocalDate workDate;
    private LocalTime startTime;
    private LocalTime endTime;
    /** WORKING/LEAVE */
    private String status;
}
