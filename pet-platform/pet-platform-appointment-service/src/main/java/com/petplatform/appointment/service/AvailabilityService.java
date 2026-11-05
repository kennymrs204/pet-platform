package com.petplatform.appointment.service;

import com.petplatform.appointment.mapper.AppointmentCommandMapper;
import com.petplatform.appointment.mapper.AppointmentConfigMapper;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.appointment.api.AppointmentErrorCode;
import com.petplatform.catalog.api.CatalogQueryApi;
import com.petplatform.catalog.api.dto.SkillRef;
import com.petplatform.catalog.api.dto.StoreServiceView;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.schedule.api.ResourceOccupancyApi;
import com.petplatform.schedule.api.ScheduleLockApi;
import com.petplatform.schedule.api.dto.KennelResourceView;
import com.petplatform.schedule.api.dto.OccupancyView;
import com.petplatform.schedule.api.dto.ScheduleLockView;
import com.petplatform.store.api.StoreQueryApi;
import com.petplatform.store.api.dto.StoreView;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 可约判定统一算法体（第五阶段 §3.1/§6，ADR-016 §7）：
 * availability 查询与创建预约共用本类——"页面可约但提交必失败"的两套算法从此绝迹。
 * 槽位模型：30min 粒度 slot_no 0..47（ADR-005-2）。
 */
@Service
public class AvailabilityService {

    public static final int SLOT_MINUTES = 30;
    public static final int SLOTS_PER_DAY = 48;
    private static final Set<String> KENNEL_TYPES =
            Set.of("KENNEL_SMALL", "KENNEL_MEDIUM", "KENNEL_LARGE", "CAT_ROOM");

    private final CatalogQueryApi catalogApi;
    private final StoreQueryApi storeApi;
    private final ResourceOccupancyApi occupancyApi;
    private final ScheduleLockApi scheduleLockApi;
    private final EmployeeQueryApi employeeApi;
    private final AppointmentCommandMapper appointmentMapper;
    private final AppointmentConfigMapper configMapper;

    public AvailabilityService(CatalogQueryApi catalogApi, StoreQueryApi storeApi,
                               ResourceOccupancyApi occupancyApi, ScheduleLockApi scheduleLockApi,
                               EmployeeQueryApi employeeApi, AppointmentCommandMapper appointmentMapper,
                               AppointmentConfigMapper configMapper) {
        this.catalogApi = catalogApi;
        this.storeApi = storeApi;
        this.occupancyApi = occupancyApi;
        this.scheduleLockApi = scheduleLockApi;
        this.employeeApi = employeeApi;
        this.appointmentMapper = appointmentMapper;
        this.configMapper = configMapper;
    }

    /** C3/C4 共享闸：服务存在且 ON、门店存在且 OPEN。 */
    public StoreServiceView requireBookableService(Long storeServiceId) {
        StoreServiceView svc = catalogApi.storeServiceOf(storeServiceId);
        if (svc == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        StoreView store = storeApi.store(svc.getStoreId());
        if (store == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        if (!"ON".equals(svc.getStatus())) {
            throw new BusinessException(AppointmentErrorCode.SERVICE_OFF_SHELF);
        }
        if (!"OPEN".equals(store.getStatus())) {
            throw new BusinessException(AppointmentErrorCode.STORE_CLOSED);
        }
        return svc;
    }

    /** 配置整数读取（sys_config），异常回落默认。 */
    public int configInt(String key, int defaultValue) {
        try {
            String v = configMapper.valueOf(key);
            return v == null || v.isBlank() ? defaultValue : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static int slotOf(LocalTime time) {
        return time.getHour() * 2 + time.getMinute() / 30;
    }

    public static LocalTime timeOf(int slot) {
        return LocalTime.of((slot * SLOT_MINUTES) / 60, (slot * SLOT_MINUTES) % 60);
    }

    /**
     * INSTANT 候选槽（§6 步骤 2/5）：营业扣休息、30min 对齐、min_lead 时限、[fromSlot,toSlotExclusive)。
     */
    public List<int[]> candidateWindows(StoreView store, int durationMin, LocalDate date, LocalDateTime now) {
        int dur = durationMin / SLOT_MINUTES;
        if (dur <= 0) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "服务时长须为30分钟的倍数且不小于30分钟");
        }
        int open = Math.max(slotOf(store.getBusinessStart()), 0);
        int close = Math.min(slotOf(store.getBusinessEnd()), SLOTS_PER_DAY);
        int minLeadMin = configInt("appointment.min_lead_hours", 2) * 60;
        if (date.equals(now.toLocalDate())) {
            LocalDateTime earliest = now.plusMinutes(minLeadMin);
            if (!earliest.toLocalDate().equals(date)) {
                // min_lead 越过午夜：当天营业时段内已无满足提前量的槽位，全日不可约（勿用 LocalTime 比较，会回绕）
                return List.of();
            }
            LocalTime earliestTime = earliest.toLocalTime();
            int s = 0;
            while (s < SLOTS_PER_DAY && timeOf(s).isBefore(earliestTime)) {
                s++;
            }
            open = Math.max(open, s);
        }
        List<int[]> windows = new ArrayList<>();
        int breakFrom = store.getBreakStart() == null ? -1 : slotOf(store.getBreakStart());
        int breakTo = store.getBreakEnd() == null ? -1 : slotOf(store.getBreakEnd());
        for (int s = open; s + dur <= close; s++) {
            int e = s + dur;
            if (breakFrom >= 0 && s < breakTo && e > breakFrom) {
                continue; // 与休息时段重叠
            }
            windows.add(new int[]{s, e});
        }
        return windows;
    }

    private static int toMinutes(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }
    /** 资源过滤（§6 步骤 3）：存在一台资源整窗空闲即可（id 升序取首个，§2.2）。 */
    public Long pickFreeResource(String type, LocalDate date, Long storeId, int fromSlot, int toSlot) {
        return pickFreeResource(type, date, storeId, fromSlot, toSlot, null);
    }

    /**
     * 改期候选选择/锁后复检共用：excludeAppointmentId 非空时只忽略该预约自己的旧时段占用，
     * 其他预约的占用仍参与冲突判断。
     */
    public Long pickFreeResource(String type, LocalDate date, Long storeId, int fromSlot, int toSlot,
                                 Long excludeAppointmentId) {
        List<KennelResourceView> resources = occupancyApi.availableResources(storeId, type);
        if (resources.isEmpty()) {
            return null;
        }
        Map<Long, Set<Integer>> occupied = new HashMap<>(
                occupancyApi.daySlotOccupancy(storeId, type, date));
        if (excludeAppointmentId != null) {
            Map<Long, Set<Integer>> own =
                    occupancyApi.timeSlotOccupancyOfAppointment(excludeAppointmentId, date);
            own.forEach((resourceId, ownSlots) -> occupied.computeIfPresent(resourceId, (id, busy) -> {
                Set<Integer> remaining = new HashSet<>(busy);
                remaining.removeAll(ownSlots);
                return remaining.isEmpty() ? null : remaining;
            }));
        }
        for (KennelResourceView r : resources) {
            Set<Integer> busy = occupied.getOrDefault(r.getId(), Set.of());
            boolean free = true;
            for (int s = fromSlot; s < toSlot; s++) {
                if (busy.contains(s)) {
                    free = false;
                    break;
                }
            }
            if (free) {
                return r.getId();
            }
        }
        return null;
    }

    /** MULTI_DAY 空闲笼位（ADR-016 §6）：类型序=基准尺寸起允许升级；区间逐日无占用。 */
    public List<KennelResourceView> freeKennels(Long storeId, String species, String sizeCategory,
                                                LocalDate from, LocalDate toExclusive) {
        return freeKennels(storeId, species, sizeCategory, from, toExclusive, null);
    }

    /**
     * 改期候选选择：excludeAppointmentId 非空时只忽略该预约自身在目标区间内的 DAY 预占，
     * 其他预约占用仍保留。
     */
    public List<KennelResourceView> freeKennels(Long storeId, String species, String sizeCategory,
                                                LocalDate from, LocalDate toExclusive,
                                                Long excludeAppointmentId) {
        List<String> types = kennelTypes(species, sizeCategory);
        Set<Long> occupied = new HashSet<>(
                occupancyApi.kennelIdsOccupiedBetween(storeId, from, toExclusive));
        if (excludeAppointmentId != null) {
            for (OccupancyView own : occupancyApi.occupancyOfAppointment(excludeAppointmentId)) {
                if (!own.getWorkDate().isBefore(from) && own.getWorkDate().isBefore(toExclusive)) {
                    occupied.remove(own.getResourceId());
                }
            }
        }
        List<KennelResourceView> result = new ArrayList<>();
        for (String type : types) {
            occupancyApi.availableResources(storeId, type).stream()
                    .filter(r -> !occupied.contains(r.getId()))
                    .forEach(result::add);
        }
        return result;
    }

    /** 尺寸→笼位类型序列（含升级路径；CAT 仅 CAT_ROOM）。 */
    public static List<String> kennelTypes(String species, String sizeCategory) {
        if ("CAT".equals(species)) {
            return List.of("CAT_ROOM");
        }
        int base = switch (sizeCategory == null ? "" : sizeCategory) {
            case "MEDIUM" -> 2;
            case "LARGE" -> 3;
            default -> 1;
        };
        List<String> types = new ArrayList<>();
        String[] all = {"KENNEL_SMALL", "KENNEL_MEDIUM", "KENNEL_LARGE"};
        for (int i = base - 1; i < all.length; i++) {
            types.add(all[i]);
        }
        return types;
    }

    /**
     * C7 员工四条（§3.1）：班次行（调用方取：创建=FOR UPDATE 锁读 / 查询=普通读）→
     * 条1 归属由锁读非空蕴含（emp 与店一致性由创建前置 briefs/isActive 校验）；
     * 条2 窗口 ⊆ 班次扣休息；条3 重叠+buffer；条4 技能子集。
     */
    public void requireEmployeeBookable(ScheduleLockView shift, StoreView store, LocalDate date,
                                        int fromSlot, int toSlot, Set<Long> requiredSkillIds,
                                        Long employeeId) {
        if (shift == null || !"WORKING".equals(shift.getStatus())) {
            throw new BusinessException(AppointmentErrorCode.EMPLOYEE_NOT_WORKING);
        }
        int open = slotOf(store.getBusinessStart());
        int close = slotOf(store.getBusinessEnd());
        int breakFrom = store.getBreakStart() == null ? -1 : slotOf(store.getBreakStart());
        int breakTo = store.getBreakEnd() == null ? -1 : slotOf(store.getBreakEnd());
        int ws = Math.max(open, slotOf(shift.getStartTime()));
        int we = Math.min(close, slotOf(shift.getEndTime()));
        if (fromSlot < ws || toSlot > we
                || (breakFrom >= 0 && fromSlot < breakTo && toSlot > breakFrom)) {
            throw new BusinessException(AppointmentErrorCode.EMPLOYEE_NOT_WORKING, "所选时段不在员工班次内");
        }
        if (!requiredSkillIds.isEmpty()) {
            Set<Long> owned = Set.copyOf(employeeApi.employeeSkillIds(employeeId));
            if (!owned.containsAll(requiredSkillIds)) {
                throw new BusinessException(AppointmentErrorCode.EMPLOYEE_SKILL_MISMATCH);
            }
        }
        int buffer = configInt("appointment.employee_buffer_minutes", 0);
        List<AppointmentCommandMapper.TimeRangeRow> busy = appointmentMapper.selectEmployeeBusyRanges(employeeId, date);
        for (AppointmentCommandMapper.TimeRangeRow r : busy) {
            int rs = slotOf(r.getStartTime());
            int re = slotOf(r.getEndTime());
            if (fromSlot < re + buffer && toSlot + buffer > rs) {
                throw new BusinessException(AppointmentErrorCode.EMPLOYEE_TIME_CONFLICT);
            }
        }
    }

    public static Set<Long> requiredSkillIds(StoreServiceView svc) {
        List<SkillRef> skills = svc.getSkills();
        return skills == null ? Set.of()
                : skills.stream().map(SkillRef::getSkillId).collect(Collectors.toSet());
    }

    /**
     * §7 查询侧员工候选（allowUserPick=true）：当日 WORKING 班次 ∧ 本店在职 ∧ 技能超集。
     * 在职闸排除全局非 ACTIVE（离职/停用）或已不再拥有本店 ACTIVE 归属（移出本店）的员工——
     * 排班仍在但人已不能履约者不得进入可约候选（与创建路径 isActiveInStore 同口径）。
     * 只读非终判——时段重叠/buffer 由创建路径 requireEmployeeBookable 锁内复核裁决。
     */
    public List<ScheduleLockView> workingShiftsWithSkill(Long storeId, LocalDate date, Set<Long> requiredSkillIds) {
        List<ScheduleLockView> shifts = scheduleLockApi.workingShiftsOfStore(storeId, date);
        if (shifts.isEmpty()) {
            return shifts;
        }
        boolean needSkill = requiredSkillIds != null && !requiredSkillIds.isEmpty();
        // 同一员工在本次查询里可能对应多个班次；isActiveInStore 结果按员工本地缓存，
        // 避免对同一 (employeeId, storeId) 重复执行完全相同的校验（仅本次调用内有效，无跨请求缓存）。
        Map<Long, Boolean> activeInStore = new HashMap<>();
        List<ScheduleLockView> out = new ArrayList<>();
        for (ScheduleLockView s : shifts) {
            Long employeeId = s.getEmployeeId();
            if (!activeInStore.computeIfAbsent(employeeId, id -> employeeApi.isActiveInStore(id, storeId))) {
                continue;
            }
            if (needSkill) {
                Set<Long> owned = Set.copyOf(employeeApi.employeeSkillIds(employeeId));
                if (!owned.containsAll(requiredSkillIds)) {
                    continue;
                }
            }
            out.add(s);
        }
        return out;
    }

    public static boolean isKennelType(String type) {
        return type != null && KENNEL_TYPES.contains(type);
    }
}
