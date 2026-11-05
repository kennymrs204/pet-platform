package com.petplatform.schedule.api;

import com.petplatform.schedule.api.dto.ScheduleLockView;

import java.time.LocalDate;

/**
 * 员工排班行锁（ADR-016 §6 / 第五阶段 §2.3）：创建/分配预约事务内 FOR UPDATE 锁定
 * sch_schedule 行（uk_emp_date 保证锁点唯一），把同员工同日的并发创建串行化——
 * 员工双约的最终防线，即使 Redis 锁失效也不破。仅供 appointment 模块在自身事务内调用。
 */
public interface ScheduleLockApi {

    /**
     * 锁定员工当日班次行；返回含 status（WORKING/LEAVE）；无班次行返回 null。
     * 调用方事务必须已开启（@Transactional REQUIRED），锁随事务提交释放。
     */
    ScheduleLockView lockScheduleForCreate(Long employeeId, LocalDate workDate);

    /**
     * 某店某日 WORKING 班次列表（ADR-016 §7 可约查询员工候选读侧，普通读不加锁、非最终判定）。
     * 供 appointment availability 组装 employeeOptions；重叠/buffer 由创建路径锁内复核裁决。
     */
    java.util.List<ScheduleLockView> workingShiftsOfStore(Long storeId, LocalDate workDate);
}
