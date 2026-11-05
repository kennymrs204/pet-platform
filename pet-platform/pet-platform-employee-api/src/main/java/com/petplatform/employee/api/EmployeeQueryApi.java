package com.petplatform.employee.api;

import com.petplatform.employee.api.dto.EmployeeBrief;
import com.petplatform.employee.api.dto.EmployeeIdentityView;

import java.util.Collection;
import java.util.List;

/**
 * 员工摘要查询（第二阶段 §employee 暴露 api：EmployeeQueryApi（员工摘要、所属门店、在职校验））。
 */
public interface EmployeeQueryApi {

    /**
     * 用户在指定门店的在职（ACTIVE 且归属 ACTIVE）员工 id；非员工/离职/不归属该店返回 null。
     * 供 app 权限装配时填充 AuthPrincipal.employeeId（TASK 数据范围的 WHERE 依据）。
     */
    Long employeeIdInStore(Long userId, Long storeId);

    /**
     * 用户的 ACTIVE 员工身份 + 全部 ACTIVE 门店归属与角色（ADR-012，emp:self:view 的 SELF 数据范围）。
     * 排序确定性：主门店（is_primary=1）优先，其次 store_id 升序；同店内角色码升序。
     * 无 ACTIVE 员工记录返回 null；员工存在但无 ACTIVE 归属时 stores 为空列表。
     * storeName 为占位 null，待 store 段补值（表 Owner 边界，见 StoreIdentityView 注释）。
     */
    EmployeeIdentityView activeIdentityOf(Long userId);

    /**
     * 批量取员工简要信息（姓名 + 在指定门店的角色码），供 B 端列表（排班/预约/托管）回显。
     * 不存在的 employeeId 静默跳过；顺序不保证。
     */
    List<EmployeeBrief> briefsInStore(Collection<Long> employeeIds, Long storeId);

    /**
     * 员工是否"该店有效员工"：emp_employee.status=ACTIVE ∧ 存在该店 ACTIVE 归属
     * （ADR-015 §3 入住时护理员校验；briefsInStore 仅回显不校验，不可替代本方法）。
     */
    boolean isActiveInStore(Long employeeId, Long storeId);

    /** 员工技能 id 集合（emp_employee_skill，表 Owner=employee；ADR-016 §3.1 条4 技能匹配输入）。 */
    java.util.List<Long> employeeSkillIds(Long employeeId);

    /**
     * 批量员工 id → 登录主体 user_id（仅 ACTIVE 员工；查无/离职者静默缺席）。
     * 通知桥用（ADR-052）：站内信收件人是 usr_user.id，而事件里只有 emp_employee.id。
     */
    java.util.Map<Long, Long> userIdsByEmployeeIds(java.util.Collection<Long> employeeIds);
}
