package com.petplatform.app.web;

import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.time.BusinessDates;
import com.petplatform.employee.api.StoreEmployeeQueryApi;
import com.petplatform.employee.api.dto.EmployeeRosterView;
import com.petplatform.schedule.api.ScheduleQueryApi;
import com.petplatform.schedule.api.dto.ScheduleDayView;
import com.petplatform.user.api.UserAccountApi;
import com.petplatform.user.api.dto.UserProfileView;
import lombok.Builder;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * B 端员工管理（P8 §4.3 :225 GET /store/employees，权限码 emp:view）。
 *
 * 落 app 装配层的原因（ADR-024 §3）：一行员工要拼三张 Owner 的表——
 * employee（档案/角色/技能/归属）、user（手机号脱敏）、schedule（今日在班）；
 * 依赖矩阵里 employee 不依赖 schedule，模块内拼不出来，app 是唯一可同时依赖全部模块的组合根。
 *
 * 脚注④落地方式（ADR-024 §2）：**是否持有 emp:update** 决定字段披露。
 * 前台只有 emp:view → 手机号/入职日期/技能/门店归属一律 null，且**连 profilesByIds 都不调用**
 * （不为了"反正会丢掉"去读一遍明文手机号）。
 */
@RestController
@RequestMapping("/api/v1/store/employees")
public class StoreEmployeeController {

    private static final Set<String> TABS = Set.of("ALL", "ON_DUTY", "LEAVE", "IDLE", "OFF");
    private static final Set<String> OFF_STATUSES = Set.of("RESIGNED", "SUSPENDED");
    private static final String CODE_MANAGE = "emp:update";

    private final StoreEmployeeQueryApi rosterQueryApi;
    private final ScheduleQueryApi scheduleQueryApi;
    private final UserAccountApi userAccountApi;

    public StoreEmployeeController(StoreEmployeeQueryApi rosterQueryApi,
                                   ScheduleQueryApi scheduleQueryApi,
                                   UserAccountApi userAccountApi) {
        this.rosterQueryApi = rosterQueryApi;
        this.scheduleQueryApi = scheduleQueryApi;
        this.userAccountApi = userAccountApi;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('perm:emp:view')")
    public Result<List<EmployeeRow>> list(@RequestParam(required = false) String tab,
                                          @RequestParam(required = false) String keyword) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        String theTab = tab == null || tab.isBlank() ? "ALL" : tab;
        if (!TABS.contains(theTab)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "员工分类取值非法");
        }
        boolean full = principal.permissions().contains(CODE_MANAGE);
        LocalDate today = BusinessDates.today();

        List<EmployeeRosterView> roster = rosterQueryApi.rosterInStore(storeId);
        Map<Long, ScheduleDayView> shifts = scheduleQueryApi.schedulesBetween(storeId, today, today, null).stream()
                .collect(Collectors.toMap(ScheduleDayView::getEmployeeId, Function.identity(), (a, b) -> a));
        Map<Long, UserProfileView> profiles = full ? profilesOf(roster) : Map.of();

        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<EmployeeRow> rows = roster.stream()
                .map(r -> toRow(r, shifts.get(r.getEmployeeId()), profiles.get(r.getUserId()), full, today))
                .filter(row -> matchesTab(row, theTab))
                .filter(row -> kw.isEmpty() || hitsKeyword(row, kw))
                .toList();
        return Result.ok(rows);
    }

    /** 只读持有者（前台）不查手机号——ADR-024 §2。 */
    private Map<Long, UserProfileView> profilesOf(List<EmployeeRosterView> roster) {
        Set<Long> userIds = roster.stream().map(EmployeeRosterView::getUserId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        return userAccountApi.profilesByIds(userIds).stream()
                .collect(Collectors.toMap(UserProfileView::getUserId, Function.identity(), (a, b) -> a));
    }

    private static boolean matchesTab(EmployeeRow row, String tab) {
        // ALL 必须最先短路：否则下面的「非 OFF 页签排除停用离职」会把离职者从「全部」里一起吞掉
        if ("ALL".equals(tab)) {
            return true;
        }
        boolean off = OFF_STATUSES.contains(row.getStatus());
        if ("OFF".equals(tab)) {
            return off;
        }
        if (off) {
            return false;
        }
        return switch (tab) {
            case "ON_DUTY" -> row.getTodayShift() != null && "WORKING".equals(row.getTodayShift().getStatus());
            case "LEAVE" -> (row.getTodayShift() != null && "LEAVE".equals(row.getTodayShift().getStatus()))
                    || "ON_LEAVE".equals(row.getStatus());
            // IDLE 必须排除已被 LEAVE 页签收走的「请假中但当天无排班行」，否则同一人进两个页签、四态求和 > 全部
            default -> row.getTodayShift() == null && !"ON_LEAVE".equals(row.getStatus());
        };
    }

    private static boolean hitsKeyword(EmployeeRow row, String kw) {
        return contains(row.getName(), kw) || contains(row.getEmployeeNo(), kw) || contains(row.getRolesLabel(), kw);
    }

    private static boolean contains(String value, String kw) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(kw);
    }

    private static EmployeeRow toRow(EmployeeRosterView r, ScheduleDayView shift, UserProfileView profile,
                                     boolean full, LocalDate today) {
        return EmployeeRow.builder()
                .employeeId(r.getEmployeeId())
                .name(r.getName())
                .employeeNo(r.getEmployeeNo())
                .status(r.getStatus())
                .rolesLabel(r.getRolesLabel())
                .roleCodes(r.getRoleCodes())
                .todayShift(shift == null ? null : toShift(shift, today))
                .hiredAt(full ? r.getHiredAt() : null)
                .leftAt(full ? r.getLeftAt() : null)
                .phoneMasked(full ? maskPhone(profile == null ? null : profile.getPhone()) : null)
                .skillCodes(full ? r.getSkillCodes() : null)
                .affiliations(full ? r.getAffiliations() : null)
                .activeStoreCount(full ? r.getActiveStoreCount() : null)
                // 当前店归属状态不受脚注④裁剪：它只说"本店还有没有他"，不含他店/手机号/技能，
                // 而前台（emp:view）也要正确区分"本店在职"与"已移出本店"。
                .currentStoreAffiliation(r.getCurrentStoreAffiliation())
                .build();
    }

    private static ShiftVO toShift(ScheduleDayView s, LocalDate today) {
        return ShiftVO.builder()
                .scheduleId(s.getId())
                .workDate(s.getWorkDate() == null ? today : s.getWorkDate())
                .startTime(s.getStartTime())
                .endTime(s.getEndTime())
                .status("LEAVE".equals(s.getStatus()) ? "LEAVE" : "WORKING")
                .leaveStatus(s.getLeaveStatus() == null ? "NONE" : s.getLeaveStatus())
                // ScheduleDayView 未导出 leave_reason（ADR-024 §4）：不改 schedule 契约，前端只显示"请假"
                .leaveReason(null)
                .build();
    }

    /** 139****4004；异常长度只留前 3 位，绝不回退成明文。 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String p = phone.trim();
        if (p.length() >= 7) {
            return p.substring(0, 3) + "****" + p.substring(p.length() - 4);
        }
        return p.substring(0, Math.min(3, p.length())) + "****";
    }

    private Long requireStoreCtx(AuthPrincipal principal) {
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }

    /** 员工行视图（long/Long → 全局 Jackson 字符串化；null = 按脚注④不披露，非"没有值"）。 */
    @Data
    @Builder
    public static class EmployeeRow {
        private Long employeeId;
        private String name;
        private String employeeNo;
        private String status;
        private String rolesLabel;
        private List<String> roleCodes;
        private ShiftVO todayShift;
        private LocalDate hiredAt;
        private LocalDate leftAt;
        private String phoneMasked;
        private List<String> skillCodes;
        private List<EmployeeRosterView.StoreAffiliation> affiliations;
        private Long activeStoreCount;
        /** 当前请求门店归属状态 ACTIVE/ENDED；不受脚注④裁剪，emp:view 即可见 */
        private String currentStoreAffiliation;
    }

    @Data
    @Builder
    public static class ShiftVO {
        private Long scheduleId;
        private LocalDate workDate;
        private LocalTime startTime;
        private LocalTime endTime;
        private String status;
        private String leaveStatus;
        private String leaveReason;
    }
}
