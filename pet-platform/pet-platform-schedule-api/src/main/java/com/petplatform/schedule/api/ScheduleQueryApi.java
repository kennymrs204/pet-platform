package com.petplatform.schedule.api;

import com.petplatform.schedule.api.dto.ScheduleDayView;

import java.time.LocalDate;
import java.util.List;

/**
 * 排班只读查询（表 Owner：schedule 模块）。B 端首页「今日排班/在岗员工数」数据源（ADR-013）。
 */
public interface ScheduleQueryApi {

    /**
     * 门店区间排班（含 LEAVE 行，状态由消费方展示）。确定性排序：work_date → start_time → employee_id。
     * employeeId 非空时只返回该员工行（STORE 范围内再收 TASK 范围）。
     */
    List<ScheduleDayView> schedulesBetween(Long storeId, LocalDate startDate, LocalDate endDate, Long employeeId);

    /** 今日在班员工数 = COUNT(sch_schedule WHERE store, work_date, status='WORKING')。 */
    long countOnDuty(Long storeId, LocalDate workDate);
}
