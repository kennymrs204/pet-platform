package com.petplatform.employee.service;

import com.petplatform.employee.api.StoreEmployeeQueryApi;
import com.petplatform.employee.api.dto.EmployeeRosterView;
import com.petplatform.employee.mapper.StoreEmployeeQueryMapper;
import com.petplatform.store.api.StoreQueryApi;
import com.petplatform.store.api.dto.StoreView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 本店员工名册实现（ADR-024）。
 * 门店名经 {@code StoreQueryApi} 逐个回填（同店只查一次）——不在 SQL 里 join sto_store，
 * 那是绕过表 Owner 的跨模块数据耦合。
 */
@Service
public class StoreEmployeeQueryService implements StoreEmployeeQueryApi {

    private final StoreEmployeeQueryMapper rosterMapper;
    private final StoreQueryApi storeQueryApi;

    public StoreEmployeeQueryService(StoreEmployeeQueryMapper rosterMapper, StoreQueryApi storeQueryApi) {
        this.rosterMapper = rosterMapper;
        this.storeQueryApi = storeQueryApi;
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeRosterView> rosterInStore(Long storeId) {
        if (storeId == null || storeId == 0L) {
            return List.of();
        }
        List<StoreEmployeeQueryMapper.RosterRow> rows = rosterMapper.selectRoster(storeId);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(StoreEmployeeQueryMapper.RosterRow::getEmployeeId).toList();

        List<StoreEmployeeQueryMapper.AffiliationRow> affRows = rosterMapper.selectAffiliations(ids);
        Map<Long, String> storeNames = storeNames(affRows.stream()
                .map(StoreEmployeeQueryMapper.AffiliationRow::getStoreId)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        Map<Long, List<EmployeeRosterView.StoreAffiliation>> affs = affRows.stream()
                .collect(Collectors.groupingBy(StoreEmployeeQueryMapper.AffiliationRow::getEmployeeId,
                        Collectors.mapping(a -> toAffiliation(a, storeNames), Collectors.toList())));

        Map<Long, List<String>> skills = rosterMapper.selectSkills(ids).stream()
                .collect(Collectors.toMap(StoreEmployeeQueryMapper.SkillRow::getEmployeeId,
                        r -> split(r.getSkillCodes()), (a, b) -> a));

        return rows.stream().map(r -> {
            List<EmployeeRosterView.StoreAffiliation> myAffs = affs.getOrDefault(r.getEmployeeId(), List.of());
            return EmployeeRosterView.builder()
                    .employeeId(r.getEmployeeId())
                    .userId(r.getUserId())
                    .name(r.getName())
                    .employeeNo(r.getEmployeeNo())
                    .status(r.getStatus())
                    .hiredAt(r.getHiredAt())
                    .leftAt(r.getLeftAt())
                    .roleCodes(split(r.getRoleCodes()))
                    .rolesLabel(r.getRolesLabel())
                    .skillCodes(skills.getOrDefault(r.getEmployeeId(), List.of()))
                    .affiliations(myAffs)
                    .activeStoreCount(myAffs.stream()
                            .filter(a -> "ACTIVE".equals(a.getStatus())).count())
                    .currentStoreAffiliation(activeInStore(myAffs, storeId) ? "ACTIVE" : "ENDED")
                    .build();
        }).toList();
    }

    /**
     * 当前请求门店是否仍是该员工的 ACTIVE 归属（EXISTS 语义，同 {@code isActiveInStore}）。
     * 只要有本店的 ACTIVE 行就算 ACTIVE——调店历史留下的本店 ENDED 行不影响判定（旧行 ENDED + 新行 ACTIVE）。
     * 无本店 ACTIVE 行则 ENDED（含"仅有本店 ENDED 行"的历史在册者）。
     */
    private static boolean activeInStore(List<EmployeeRosterView.StoreAffiliation> myAffs, Long storeId) {
        return myAffs.stream().anyMatch(a -> storeId.equals(a.getStoreId()) && "ACTIVE".equals(a.getStatus()));
    }

    /** 门店名批量解析：同 id 只查一次；门店已删/查不到 → null（前端回退「门店 {id}」，不编店名）。 */
    private Map<Long, String> storeNames(Set<Long> storeIds) {
        Map<Long, String> names = new HashMap<>();
        for (Long id : storeIds) {
            StoreView view = storeQueryApi.store(id);
            names.put(id, view == null ? null : view.getName());
        }
        return names;
    }

    private static EmployeeRosterView.StoreAffiliation toAffiliation(
            StoreEmployeeQueryMapper.AffiliationRow a, Map<Long, String> names) {
        return EmployeeRosterView.StoreAffiliation.builder()
                .storeId(a.getStoreId())
                .storeName(names.get(a.getStoreId()))
                .isPrimary(Boolean.TRUE.equals(a.getIsPrimary()))
                .status(a.getStatus())
                .startedAt(a.getStartedAt())
                .endedAt(a.getEndedAt())
                .roleCodes(split(a.getRoleCodes()))
                .build();
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return List.of(csv.split(","));
    }
}
