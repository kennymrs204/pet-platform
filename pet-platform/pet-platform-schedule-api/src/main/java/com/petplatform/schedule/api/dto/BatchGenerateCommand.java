package com.petplatform.schedule.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 批量排班请求（ADR-034，P8 §4.3 :221 {@code POST /schedules/batch-generate}）。
 *
 * <p>{@code uk_emp_date(employee_id, work_date)} 不带 store_id，所以"一人一天"在全平台只有一个班：
 * 撞已有行的处理口径见 {@code ScheduleCommandApi}，不是覆盖也不是整批回滚。
 */
@Data
public class BatchGenerateCommand {

    @NotEmpty(message = "至少选择一名员工")
    @Size(max = 50, message = "一次最多 50 名员工")
    private List<Long> employeeIds;

    @NotNull(message = "startDate 必填")
    private LocalDate startDate;

    @NotNull(message = "endDate 必填")
    private LocalDate endDate;

    @NotNull(message = "上班时间必填")
    private LocalTime startTime;

    @NotNull(message = "下班时间必填")
    private LocalTime endTime;

    /**
     * ISO 星期过滤（1=周一 … 7=周日）；空或 null = 区间内每天都排。
     * 存在的理由：真实排班几乎从不把休息日排进班次，没有这个口子店长只能连排 7 天或逐日点。
     */
    @Size(max = 7, message = "weekdays 最多 7 项")
    private List<Integer> weekdays;
}
