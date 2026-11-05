package com.petplatform.employee.service;

import com.petplatform.employee.api.EmployeeRosterApi;
import com.petplatform.employee.api.dto.StoreStaffView;
import com.petplatform.employee.mapper.EmployeeRosterMapper;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 本店在职员工名条（ADR-018）。纯只读，跨模块消费方（boarding）只经 EmployeeRosterApi 出数，
 * 不直查 emp_* 表——表 Owner 边界不变。
 */
@Service
public class EmployeeRosterService implements EmployeeRosterApi {

    private final EmployeeRosterMapper mapper;

    public EmployeeRosterService(EmployeeRosterMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<StoreStaffView> staffInStore(Long storeId) {
        if (storeId == null || storeId == 0L) {
            return List.of();
        }
        return mapper.selectStaffInStore(storeId).stream()
                .map(row -> StoreStaffView.builder()
                        .employeeId(row.getEmployeeId())
                        .name(row.getName())
                        .roleCodes(splitCodes(row.getRoleCodes()))
                        .rolesLabel(row.getRolesLabel())
                        .build())
                .toList();
    }

    @Override
    public java.util.Set<Long> employeeUserIds() {
        java.util.Set<Long> ids = mapper.selectEmployeeUserIds();
        return ids == null ? java.util.Set.of() : ids;
    }

    private static List<String> splitCodes(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
