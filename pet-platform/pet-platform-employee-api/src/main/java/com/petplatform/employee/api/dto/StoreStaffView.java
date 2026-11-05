package com.petplatform.employee.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 本店在职员工名条（跨模块只读投影，表 Owner=employee）。
 * 与 EmployeeBrief 的区别：本视图面向"入住时指派主责护理员"的**下拉选择**，
 * 附带门店角色中文名供 UI 直接展示；EmployeeBrief 用于列表行回显（按 id 批量取）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreStaffView {

    private Long employeeId;
    private String name;
    /** 该店角色码（升序；无角色为空列表） */
    private List<String> roleCodes;
    /** 角色中文名拼接，如「寄养护理员、店长」；无角色为 null */
    private String rolesLabel;
}
