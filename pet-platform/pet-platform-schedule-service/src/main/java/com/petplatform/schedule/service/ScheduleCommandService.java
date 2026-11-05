package com.petplatform.schedule.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.time.BusinessDates;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.schedule.api.ScheduleCommandApi;
import com.petplatform.schedule.api.dto.BatchGenerateCommand;
import com.petplatform.schedule.api.dto.BatchGenerateResult;
import com.petplatform.schedule.api.dto.ScheduleBrief;
import com.petplatform.schedule.mapper.ScheduleCommandMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 排班写侧（ADR-034）。只做"建/改/删排班行"，不碰 {@code status}——
 * 表模型本就是"有行才上班"，店长要让人休息就删那天的班；{@code LEAVE} 与审批属 P8 :222 那一片。
 *
 * <p>本类<b>不含</b>预约预检：schedule 不能依赖 appointment（依赖矩阵 §3.2，ArchUnit 固化），
 * 所以"这天还有没有进行中的预约"由 app 层控制器在调用本类之前问 appointment。
 */
@Service
public class ScheduleCommandService implements ScheduleCommandApi {

    private static final String SOURCE_BATCH = "BATCH";

    private final ScheduleCommandMapper mapper;
    private final EmployeeQueryApi employeeQueryApi;

    public ScheduleCommandService(ScheduleCommandMapper mapper, EmployeeQueryApi employeeQueryApi) {
        this.mapper = mapper;
        this.employeeQueryApi = employeeQueryApi;
    }

    @Override
    public ScheduleBrief findInStore(Long storeId, Long scheduleId) {
        if (storeId == null || scheduleId == null) {
            return null;
        }
        return toBrief(mapper.selectInStore(storeId, scheduleId));
    }

    @Override
    @Transactional
    public BatchGenerateResult batchGenerate(Long storeId, BatchGenerateCommand cmd) {
        List<LocalDate> days = resolveDays(cmd);
        List<Long> employeeIds = distinct(cmd.getEmployeeIds());
        if (employeeIds.isEmpty()) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "至少选择一名员工");
        }
        if (days.size() * employeeIds.size() > MAX_ROWS) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID,
                    "单次最多排 " + MAX_ROWS + " 个班（当前 " + days.size() + " 天 × " + employeeIds.size() + " 人）");
        }
        // 不属于本店的员工混在名单里 = 前端列表脏了或有人手填 id，静默跳过会把错误吞掉 → 整批拒
        for (Long employeeId : employeeIds) {
            if (!employeeQueryApi.isActiveInStore(employeeId, storeId)) {
                throw new BusinessException(CommonErrorCode.NOT_FOUND);
            }
        }

        // 一次把区间内已有的班全捞出来（含他店排的）：uk_emp_date 是全局的，撞键与否跟他店无关。
        // 存整行而不只是键——skipped 要回显"撞进了哪家店"，二次查询既啰嗦又可能在并发下回查为空。
        Map<String, ScheduleCommandMapper.ScheduleManagedRow> existing =
                mapper.selectExisting(employeeIds, cmd.getStartDate(), cmd.getEndDate()).stream()
                        .collect(Collectors.toMap(r -> key(r.getEmployeeId(), r.getWorkDate()), r -> r, (a, b) -> a));
        List<ScheduleBrief> created = new ArrayList<>();
        List<BatchGenerateResult.Skipped> skipped = new ArrayList<>();
        for (Long employeeId : employeeIds) {
            for (LocalDate day : days) {
                ScheduleCommandMapper.ScheduleManagedRow hit = existing.get(key(employeeId, day));
                if (hit != null) {
                    skipped.add(skippedOf(employeeId, day, hit));
                    continue;
                }
                long id = IdWorker.getId();
                try {
                    mapper.insert(id, employeeId, storeId, day, cmd.getStartTime(), cmd.getEndTime(), SOURCE_BATCH);
                } catch (DuplicateKeyException e) {
                    // 并发下有人在同一格抢插了：不报 500、也不覆盖他店，照 §2 的口径记为 skipped
                    skipped.add(skippedOf(employeeId, day, mapper.selectByEmployeeDate(employeeId, day)));
                    continue;
                }
                created.add(ScheduleBrief.builder()
                        .id(id)
                        .employeeId(employeeId).storeId(storeId).workDate(day)
                        .startTime(cmd.getStartTime()).endTime(cmd.getEndTime()).status("WORKING")
                        .build());
            }
        }
        return BatchGenerateResult.builder().created(created).skipped(skipped).build();
    }

    @Override
    @Transactional
    public ScheduleBrief updateTime(Long storeId, Long scheduleId, LocalTime startTime, LocalTime endTime) {
        requireTimeRange(startTime, endTime);
        ScheduleCommandMapper.ScheduleManagedRow row = scheduleId == null ? null : mapper.selectInStore(storeId, scheduleId);
        if (row == null) {
            return null;
        }
        if (mapper.updateTime(storeId, scheduleId, startTime, endTime) == 0) {
            return null;
        }
        row.setStartTime(startTime);
        row.setEndTime(endTime);
        return toBrief(row);
    }

    @Override
    @Transactional
    public int delete(Long storeId, Long scheduleId) {
        return scheduleId == null ? 0 : mapper.delete(storeId, scheduleId);
    }

    /* ---------- 校验与内部 ---------- */

    /** 区间合法性 + 按 weekdays 筛出实际要排的日子（筛空了必须报错，不能让"什么都没排"看起来像成功）。 */
    private List<LocalDate> resolveDays(BatchGenerateCommand cmd) {
        LocalDate start = cmd.getStartDate();
        LocalDate end = cmd.getEndDate();
        if (end.isBefore(start)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "endDate 不得早于 startDate");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_SPAN_DAYS) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "排班区间最长 " + MAX_SPAN_DAYS + " 天");
        }
        if (start.isBefore(BusinessDates.today())) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "不能排今天之前的班次");
        }
        requireTimeRange(cmd.getStartTime(), cmd.getEndTime());

        Set<Integer> weekdays = distinctInt(cmd.getWeekdays());
        if (!weekdays.isEmpty()) {
            for (Integer w : weekdays) {
                if (w == null || w < 1 || w > 7) {
                    throw new BusinessException(CommonErrorCode.PARAM_INVALID, "weekdays 取值必须是 1(周一)~7(周日)");
                }
            }
        }
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (weekdays.isEmpty() || weekdays.contains(d.getDayOfWeek().getValue())) {
                days.add(d);
            }
        }
        if (days.isEmpty()) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "weekdays 把区间内每天都筛掉了，没有任何日子可排");
        }
        return days;
    }

    private void requireTimeRange(LocalTime startTime, LocalTime endTime) {
        if (startTime == null || endTime == null || !endTime.isAfter(startTime)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "下班时间必须晚于上班时间");
        }
    }

    private BatchGenerateResult.Skipped skippedOf(Long employeeId, LocalDate day,
                                                 ScheduleCommandMapper.ScheduleManagedRow existing) {
        return BatchGenerateResult.Skipped.builder()
                .employeeId(employeeId).workDate(day)
                .storeId(existing == null ? null : existing.getStoreId())
                .build();
    }

    private ScheduleBrief toBrief(ScheduleCommandMapper.ScheduleManagedRow r) {
        if (r == null) {
            return null;
        }
        return ScheduleBrief.builder()
                .id(r.getId()).employeeId(r.getEmployeeId()).storeId(r.getStoreId()).workDate(r.getWorkDate())
                .startTime(r.getStartTime()).endTime(r.getEndTime()).status(r.getStatus())
                .build();
    }

    private static String key(Long employeeId, LocalDate day) {
        return employeeId + "|" + day;
    }

    private static List<Long> distinct(List<Long> raw) {
        return raw == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(raw.stream().filter(java.util.Objects::nonNull)
                .collect(Collectors.toList())));
    }

    private static Set<Integer> distinctInt(List<Integer> raw) {
        return raw == null ? Set.of() : new LinkedHashSet<>(raw.stream().filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }
}
