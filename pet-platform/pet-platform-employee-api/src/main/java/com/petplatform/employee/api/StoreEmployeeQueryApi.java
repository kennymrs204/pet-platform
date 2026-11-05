package com.petplatform.employee.api;

import com.petplatform.employee.api.dto.EmployeeRosterView;

import java.util.List;

/**
 * 本店员工名册只读查询（ADR-024；表 Owner=employee）。
 * 与 ADR-018 的 {@code EmployeeRosterApi.staffInStore}（只给"可指派在职员工"下拉）分工不同：
 * 本接口给 B 端员工管理页，需要含离职/停用与调店历史，故按「该店有过归属」出数而非只出在职。
 */
public interface StoreEmployeeQueryApi {

    /**
     * 在该店有过归属的员工（含 ENDED 归属，供「停用离职」页签呈现）。
     * 排序确定性：employee_id ASC；无角色员工仍返回（LEFT JOIN，roleCodes 空）。
     */
    List<EmployeeRosterView> rosterInStore(Long storeId);
}
