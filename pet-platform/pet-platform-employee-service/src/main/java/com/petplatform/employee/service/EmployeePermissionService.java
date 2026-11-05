package com.petplatform.employee.service;

import com.petplatform.employee.api.EmployeePermissionApi;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.employee.api.dto.EmployeeBrief;
import com.petplatform.employee.api.dto.EmployeeIdentityView;
import com.petplatform.employee.api.dto.StoreIdentityView;
import com.petplatform.employee.mapper.EmployeePermissionMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class EmployeePermissionService implements EmployeePermissionApi, EmployeeQueryApi {

    private final EmployeePermissionMapper mapper;

    public EmployeePermissionService(EmployeePermissionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Set<String> roleCodes(String roleCode) {
        return new HashSet<>(mapper.selectCodesByRoleCode(roleCode));
    }

    @Override
    public Set<String> storePermissions(Long userId, Long storeId) {
        return new HashSet<>(mapper.selectStorePermissions(userId, storeId));
    }

    @Override
    public Long employeeIdInStore(Long userId, Long storeId) {
        return mapper.selectEmployeeIdInStore(userId, storeId);
    }

    @Override
    public boolean holdsGlobalRole(Long userId, String roleCode) {
        if (userId == null || roleCode == null || roleCode.isBlank()) {
            return false;
        }
        return mapper.selectGlobalRoleHeld(userId, roleCode) > 0;
    }

    @Override
    public Set<String> rolesHolding(String permissionCode) {
        if (permissionCode == null || permissionCode.isBlank()) {
            return Set.of();
        }
        return new HashSet<>(mapper.selectRoleCodesHolding(permissionCode));
    }

    @Override
    public List<Long> userIdsWithRole(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return List.of();
        }
        return mapper.selectUserIdsWithRole(roleCode);
    }

    @Override
    public EmployeeIdentityView activeIdentityOf(Long userId) {
        EmployeePermissionMapper.EmployeeHeaderRow header = mapper.selectActiveEmployeeHeader(userId);
        if (header == null) {
            return null;
        }
        // SQL 已按 主门店→store_id→角色码 排序，LinkedHashMap 保序聚合
        Map<Long, StoreAccumulator> byStore = new LinkedHashMap<>();
        for (EmployeePermissionMapper.StoreRoleRow row : mapper.selectActiveStoreRoles(header.getId())) {
            StoreAccumulator acc = byStore.computeIfAbsent(row.getStoreId(),
                    id -> new StoreAccumulator(id, Integer.valueOf(1).equals(row.getIsPrimary())));
            if (row.getRoleCode() != null && !acc.roles.contains(row.getRoleCode())) {
                acc.roles.add(row.getRoleCode());
            }
        }
        List<StoreIdentityView> stores = new ArrayList<>(byStore.size());
        for (StoreAccumulator acc : byStore.values()) {
            stores.add(StoreIdentityView.builder()
                    .storeId(acc.storeId)
                    .storeName(null) // 占位：表 Owner 归 store 模块，第二段交付 StoreQueryApi 后由装配层补值（ADR-012）
                    .isPrimary(acc.primary)
                    .roles(List.copyOf(acc.roles))
                    .build());
        }
        return EmployeeIdentityView.builder()
                .employeeId(header.getId())
                .name(header.getName())
                .status(header.getStatus())
                .stores(stores)
                .build();
    }

    @Override
    public List<EmployeeBrief> briefsInStore(Collection<Long> employeeIds, Long storeId) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return List.of();
        }
        Map<Long, EmployeeBrief> byId = new LinkedHashMap<>();
        for (EmployeePermissionMapper.EmployeeBriefRow row : mapper.selectBriefsInStore(employeeIds, storeId)) {
            EmployeeBrief brief = byId.computeIfAbsent(row.getEmployeeId(),
                    id -> EmployeeBrief.builder().employeeId(id).name(row.getName()).roleCodes(new ArrayList<>()).build());
            if (row.getRoleCode() != null && !brief.getRoleCodes().contains(row.getRoleCode())) {
                brief.getRoleCodes().add(row.getRoleCode());
            }
        }
        return List.copyOf(byId.values());
    }

    @Override
    public boolean isActiveInStore(Long employeeId, Long storeId) {
        if (employeeId == null || employeeId == 0L || storeId == null || storeId == 0L) {
            return false;
        }
        return mapper.countActiveInStore(employeeId, storeId) > 0;
    }

    @Override
    public List<Long> employeeSkillIds(Long employeeId) {
        return employeeId == null || employeeId == 0L ? List.of() : mapper.selectSkillIds(employeeId);
    }

    @Override
    public Map<Long, Long> userIdsByEmployeeIds(Collection<Long> employeeIds) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> out = new LinkedHashMap<>();
        for (EmployeePermissionMapper.EmployeeUserIdRow row : mapper.selectUserIdsByEmployeeIds(employeeIds)) {
            if (row.getId() != null && row.getUserId() != null) {
                out.put(row.getId(), row.getUserId());
            }
        }
        return Map.copyOf(out);
    }

    private static final class StoreAccumulator {
        private final Long storeId;
        private final boolean primary;
        private final List<String> roles = new ArrayList<>();

        private StoreAccumulator(Long storeId, boolean primary) {
            this.storeId = storeId;
            this.primary = primary;
        }
    }
}
