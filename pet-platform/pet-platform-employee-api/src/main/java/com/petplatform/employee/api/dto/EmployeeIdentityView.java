package com.petplatform.employee.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 当前账号的 ACTIVE 员工身份摘要（ADR-012）。
 * 无 ACTIVE 员工记录时由 api 返回 null（或由调用方归一为 employeeId=null + stores=[]）。
 */
@Data
@Builder
public class EmployeeIdentityView {

    private Long employeeId;
    private String name;
    /** emp_employee.status，装配语义仅 ACTIVE */
    private String status;
    /** ACTIVE 门店归属，确定性排序：主门店（is_primary）优先，其次 store_id 升序 */
    private List<StoreIdentityView> stores;
}
