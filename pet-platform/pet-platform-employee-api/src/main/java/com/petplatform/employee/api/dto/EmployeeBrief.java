package com.petplatform.employee.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 员工在指定门店上下文内的简要信息（B 端列表回显：姓名 + 该店角色码）。 */
@Data
@Builder
public class EmployeeBrief {

    private Long employeeId;
    private String name;
    /** 该员工在该门店的角色码（emp_role.code）；无角色绑定时为空列表 */
    private List<String> roleCodes;
}
