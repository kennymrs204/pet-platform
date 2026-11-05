package com.petplatform.schedule.service;

import com.petplatform.schedule.api.ScheduleLockApi;
import com.petplatform.schedule.api.dto.ScheduleLockView;
import com.petplatform.schedule.mapper.ResourceOccupancyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 班次行锁（ADR-016 §6）：FOR UPDATE 加入调用方事务（REQUIRED），锁随其提交释放；
 * 无独立事务边界，脱离事务调用退化为普通读（MyBatis 自动提交），仅 appointment 创建/分配路径使用。
 */
@Service
public class ScheduleLockService implements ScheduleLockApi {

    private final ResourceOccupancyMapper mapper;

    public ScheduleLockService(ResourceOccupancyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public ScheduleLockView lockScheduleForCreate(Long employeeId, LocalDate workDate) {
        if (employeeId == null || workDate == null) {
            return null;
        }
        return mapper.lockScheduleRow(employeeId, workDate);
    }

    @Override
    public java.util.List<ScheduleLockView> workingShiftsOfStore(Long storeId, LocalDate workDate) {
        if (storeId == null || workDate == null) {
            return java.util.List.of();
        }
        return mapper.selectWorkingShiftsOfStore(storeId, workDate);
    }
}
