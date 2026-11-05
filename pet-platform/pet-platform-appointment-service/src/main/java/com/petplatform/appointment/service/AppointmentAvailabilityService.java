package com.petplatform.appointment.service;

import com.petplatform.appointment.api.dto.AvailabilityView;
import com.petplatform.catalog.api.dto.StoreServiceView;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.time.BusinessDates;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.pet.api.PetErrorCode;
import com.petplatform.pet.api.PetQueryApi;
import com.petplatform.pet.api.dto.PetSummary;
import com.petplatform.schedule.api.dto.KennelResourceView;
import com.petplatform.store.api.StoreQueryApi;
import com.petplatform.store.api.dto.StoreView;
import com.petplatform.user.api.UserPetApi;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 可约查询端点服务（ADR-016 §7）：与创建共用 AvailabilityService 算法体。
 * 只读，无锁无副作用——创建路径的锁内复核是最终判定。
 * MULTI_DAY 未传 petId 时按最小尺寸给候选（前端选宠后带 petId 重查更精确）。
 */
@Service
public class AppointmentAvailabilityService {

    private final AvailabilityService availability;
    private final StoreQueryApi storeApi;
    private final PetQueryApi petApi;
    private final UserPetApi userPetApi;
    private final EmployeeQueryApi employeeApi;

    public AppointmentAvailabilityService(AvailabilityService availability, StoreQueryApi storeApi,
                                          PetQueryApi petApi, UserPetApi userPetApi,
                                          EmployeeQueryApi employeeApi) {
        this.availability = availability;
        this.storeApi = storeApi;
        this.petApi = petApi;
        this.userPetApi = userPetApi;
        this.employeeApi = employeeApi;
    }

    /**
     * B 端可约查询（客户 360 → 新增预约弹窗）：与 C 端同一条算法体，三点差异——
     * ① 服务必须属于调用门店（防跨店查他店档期，10004 同枚举口径）；
     * ② petId 必填（B 端流程以选定宠物为前提，不做"未选宠按最小尺寸"的宽松口）；
     * ③ 宠物"可管理用户"由主登记人（OWNER）反查——B 端调用者不是顾客，canManage 的主语要换人。
     */
    public AvailabilityView storeAvailability(Long storeId, Long storeServiceId, LocalDate date, Long petId) {
        if (date == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "date必填");
        }
        if (petId == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "petId必填");
        }
        StoreServiceView svc = availability.requireBookableService(storeServiceId);
        if (!storeId.equals(svc.getStoreId())) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        Long owner = userPetApi.primaryOwnerIds(List.of(petId)).get(petId);
        if (owner == null) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "该宠物未登记主人");
        }
        return availability(owner, storeServiceId, date, petId);
    }

    public AvailabilityView availability(Long userId, Long storeServiceId, LocalDate date, Long petId) {
        if (date == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "date必填");
        }
        StoreServiceView svc = availability.requireBookableService(storeServiceId);
        StoreView store = storeApi.store(svc.getStoreId());
        boolean multiDay = "MULTI_DAY".equals(svc.getFulfillmentType());
        LocalDateTime now = LocalDateTime.now(BusinessDates.ZONE);

        List<AvailabilityView.Slot> slots = List.of();
        List<AvailabilityView.KennelOption> kennels = List.of();
        List<AvailabilityView.EmployeeOption> employeeOptions = List.of();
        if (multiDay) {
            PetSummary pet = resolvePet(userId, petId);
            String species = pet == null ? "DOG" : pet.getSpecies();
            String size = pet == null || pet.getSizeCategory() == null ? "SMALL" : pet.getSizeCategory();
            List<KennelResourceView> free = availability.freeKennels(svc.getStoreId(), species,
                    size, date, date.plusDays(1));
            kennels = free.stream().map(r -> AvailabilityView.KennelOption.builder()
                    .id(r.getId()).name(r.getName()).type(r.getType()).build()).toList();
        } else {
            String type = svc.getRequiredResourceType();
            List<int[]> windows = availability.candidateWindows(store, svc.getDurationMin(), date, now);
            List<AvailabilityView.Slot> out = new ArrayList<>();
            for (int[] w : windows) {
                if (type == null || type.isBlank()
                        || availability.pickFreeResource(type, date, svc.getStoreId(), w[0], w[1]) != null) {
                    out.add(AvailabilityView.Slot.builder()
                            .start(AvailabilityService.timeOf(w[0]).toString())
                            .end(AvailabilityService.timeOf(w[1]).toString())
                            .build());
                }
            }
            slots = out;
            if (Boolean.TRUE.equals(svc.getAllowUserPickEmployee())) {
                employeeOptions = employeeOptions(svc, date);
            }
        }
        return AvailabilityView.builder()
                .storeServiceId(storeServiceId)
                .fulfillmentType(svc.getFulfillmentType())
                .date(date.toString())
                .allowUserPickEmployee(svc.getAllowUserPickEmployee())
                .slots(slots)
                .kennels(kennels)
                .employeeOptions(employeeOptions)
                .build();
    }

    /** 员工候选=当日 WORKING 班次∧技能匹配（§7 读侧口径），按 id 升序保证分页外亦确定。 */
    private List<AvailabilityView.EmployeeOption> employeeOptions(StoreServiceView svc, LocalDate date) {
        var shifts = availability.workingShiftsWithSkill(svc.getStoreId(), date,
                AvailabilityService.requiredSkillIds(svc));
        if (shifts.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = employeeApi.briefsInStore(
                shifts.stream().map(s -> s.getEmployeeId()).toList(), svc.getStoreId()).stream()
                .collect(Collectors.toMap(b -> b.getEmployeeId(), b -> b.getName() == null ? "" : b.getName()));
        return shifts.stream()
                .sorted(Comparator.comparing(s -> s.getEmployeeId()))
                .map(s -> AvailabilityView.EmployeeOption.builder()
                        .id(s.getEmployeeId()).name(names.get(s.getEmployeeId())).build())
                .toList();
    }

    private PetSummary resolvePet(Long userId, Long petId) {
        if (petId == null) {
            return null;
        }
        if (!userPetApi.canManage(userId, petId)) {
            throw BusinessException.of(PetErrorCode.PET_FORBIDDEN);
        }
        return petApi.summaries(List.of(petId)).stream().findFirst().orElse(null);
    }
}
