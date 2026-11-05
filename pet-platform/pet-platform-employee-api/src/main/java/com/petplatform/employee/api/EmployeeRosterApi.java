package com.petplatform.employee.api;

import com.petplatform.employee.api.dto.StoreStaffView;

import java.util.List;
import java.util.Set;

/**
 * 本店在职员工名条查询（表 Owner=employee；ADR-018）。
 * 供 B 端"入住时指派主责护理员"下拉使用——调用方必须已自行完成门店上下文校验，
 * 本方法按 storeId 出数，不做授权判断（授权在 web 层的权限码，跨店由调用方兜 10003/10004）。
 */
public interface EmployeeRosterApi {

    /**
     * 该店在职可指派员工（emp_employee.status=ACTIVE ∧ 该店 emp_employee_store.status=ACTIVE）。
     * 排序确定性：employee_id 升序；无任何角色时 roleCodes/rolesLabel 为空。
     */
    List<StoreStaffView> staffInStore(Long storeId);

    /**
     * 全部员工账号的 user_id 集（不限门店，ADR-017 §1：`emp_employee.uk_user` 说明员工与客户
     * 共用 usr_user，B 端客户列表必须做此差集，否则店员会出现在客户里）。
     * 含离职/停用员工记录——剔除"人"而非只剔除"在职员工"，避免离职店员又作为客户出现。
     */
    Set<Long> employeeUserIds();
}
