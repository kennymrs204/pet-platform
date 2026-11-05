package com.petplatform.schedule.service;

import com.petplatform.schedule.api.ScheduleQueryApi;
import com.petplatform.schedule.api.dto.ScheduleDayView;
import com.petplatform.schedule.mapper.ScheduleQueryMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class ScheduleQueryService implements ScheduleQueryApi {

    private final ScheduleQueryMapper mapper;

    public ScheduleQueryService(ScheduleQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ScheduleDayView> schedulesBetween(Long storeId, LocalDate startDate, LocalDate endDate, Long employeeId) {
        return mapper.selectBetween(storeId, startDate, endDate, employeeId).stream()
                .map(r -> ScheduleDayView.builder()
                        .id(r.getId())
                        .employeeId(r.getEmployeeId())
                        .workDate(r.getWorkDate())
                        .startTime(r.getStartTime())
                        .endTime(r.getEndTime())
                        .status(r.getStatus())
                        .leaveStatus(r.getLeaveStatus())
                        .build())
                .toList();
    }

    @Override
    public long countOnDuty(Long storeId, LocalDate workDate) {
        return mapper.countOnDuty(storeId, workDate);
    }
}
