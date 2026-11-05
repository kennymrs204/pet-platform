package com.petplatform.schedule.web;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.common.time.BusinessDates;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.employee.api.dto.EmployeeBrief;
import com.petplatform.schedule.api.ScheduleQueryApi;
import com.petplatform.schedule.api.dto.ScheduleDayView;
import lombok.Builder;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * B 端门店排班查询（P8 §4.3 GET /store/schedules；ADR-010 §2 双权限码）。
 * 首页「今日排班」数据源：不传日期默认今日。只持 self:view 者 employeeId 一律被强制为本人
 * （数据范围条件，非仅靠鉴权——ADR-010 原文）。
 */
@RestController
@RequestMapping("/api/v1/store/schedules")
public class StoreScheduleController {

    // AuthPrincipal.permissions 存裸码（perm: 前缀只加在 GrantedAuthority 上，JwtAuthFilter:92-94）
    private static final String MANAGE = "schedule:manage";
    private static final long MAX_SPAN_DAYS = 31;

    private final ScheduleQueryApi scheduleQueryApi;
    private final EmployeeQueryApi employeeQueryApi;

    public StoreScheduleController(ScheduleQueryApi scheduleQueryApi, EmployeeQueryApi employeeQueryApi) {
        this.scheduleQueryApi = scheduleQueryApi;
        this.employeeQueryApi = employeeQueryApi;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('perm:schedule:self:view','perm:schedule:manage')")
    public Result<List<ScheduleBoardItem>> list(
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);

        LocalDate start = startDate != null ? startDate : BusinessDates.today();
        LocalDate end = endDate != null ? endDate : start;
        if (end.isBefore(start)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "endDate 不得早于 startDate");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_SPAN_DAYS) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "查询区间最长 " + MAX_SPAN_DAYS + " 天");
        }

        boolean canManage = principal.permissions() != null && principal.permissions().contains(MANAGE);
        // ADR-010 数据范围：只持 self:view 者只能看本人。显式传入**他人** employeeId 不再被静默替换为本人——
        // 那会把"我的排班"冒充成目标员工的排班（员工详情排班 Tab 错配根因）。按既有 10003 语义拒绝。
        // 未指定 employeeId（如首页"今日排班" todaySchedules）或指定的就是本人 → 仍返回本人排班，个人入口不受影响。
        if (!canManage && employeeId != null && !employeeId.equals(principal.employeeId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "无权查看他人排班");
        }
        Long effectiveEmployeeId = canManage ? employeeId : principal.employeeId();
        if (!canManage && (effectiveEmployeeId == null || effectiveEmployeeId == 0L)) {
            return Result.ok(List.of());
        }

        List<ScheduleDayView> rows = scheduleQueryApi.schedulesBetween(storeId, start, end, effectiveEmployeeId);
        Map<Long, EmployeeBrief> briefs = employeeQueryApi
                .briefsInStore(rows.stream().map(ScheduleDayView::getEmployeeId).distinct().toList(), storeId)
                .stream().collect(Collectors.toMap(EmployeeBrief::getEmployeeId, Function.identity(), (a, b) -> a));

        return Result.ok(rows.stream().map(r -> {
            EmployeeBrief brief = briefs.get(r.getEmployeeId());
            return ScheduleBoardItem.builder()
                    .id(r.getId())
                    .employeeId(r.getEmployeeId())
                    .employeeName(brief == null ? null : brief.getName())
                    .roleCodes(brief == null ? List.of() : brief.getRoleCodes())
                    .workDate(r.getWorkDate())
                    .startTime(r.getStartTime())
                    .endTime(r.getEndTime())
                    .status(r.getStatus())
                    .leaveStatus(r.getLeaveStatus())
                    .build();
        }).toList());
    }

    private Long requireStoreCtx(AuthPrincipal principal) {
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }

    /** 排班看板条目（web 层组装视图：排班行 + 员工姓名/角色回显）。 */
    @Data
    @Builder
    public static class ScheduleBoardItem {
        private Long id;
        private Long employeeId;
        private String employeeName;
        private List<String> roleCodes;
        private LocalDate workDate;
        private LocalTime startTime;
        private LocalTime endTime;
        private String status;
        private String leaveStatus;
    }
}
