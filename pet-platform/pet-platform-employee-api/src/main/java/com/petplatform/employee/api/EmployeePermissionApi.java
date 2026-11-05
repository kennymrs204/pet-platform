package com.petplatform.employee.api;

import java.util.List;
import java.util.Set;

/**
 * 角色→权限码加载（第二阶段 §employee 暴露 api 原文：
 * “EmployeePermissionApi（角色→权限码加载，供登录时装配 Authority）”）。
 * 供 app 的 PermissionAssembler 消费；实现只读 emp_role/emp_role_permission/
 * emp_employee/emp_employee_store/emp_employee_role。
 */
public interface EmployeePermissionApi {

    /** 某角色码的全部权限码（如 CUSTOMER）；角色不存在返回空集。 */
    Set<String> roleCodes(String roleCode);

    /**
     * 员工在指定门店上下文的权限码并集（第八阶段 §1.2：
     * emp_employee_role(当前 storeCtx) → emp_role_permission）。
     * 非 ACTIVE 员工或不归属该门店时返回空集（= 无权限，由方法级鉴权拒绝）。
     */
    Set<String> storePermissions(Long userId, Long storeId);

    /**
     * 该账号是否在**任意** ACTIVE 归属下持有某角色（ADR-044：识别"平台管理员"用）。
     *
     * <p>与 {@link #storePermissions} 的唯一差别是不带门店过滤——正因为如此它才危险，
     * 所以调用方只有一个：PermissionAssembler 的平台管理员分支。门店侧的每一次授权判定都必须继续走
     * {@code storePermissions}（"权限来自该店归属"这条地基不能从这边绕过去）。
     */
    boolean holdsGlobalRole(Long userId, String roleCode);

    /**
     * 反向查询：哪些角色持有某个权限码（ADR-047）。
     *
     * <p>存在的理由是<b>消息收件人判据不能写死角色名</b>：门店提醒该发给"能处理它的人"，
     * 而"谁能处理"唯一的真相源就是 {@code emp_role_permission}。监听器里硬编码
     * {@code roleCodes.contains("STORE_MANAGER")} 的话，任何一次合法授权（V4 给全员工补
     * {@code order:refund:view}、V5 给 ADMIN 补 17 个执行码那种）都不会自动反映到收件人上——
     * 症状是"权限改了、消息还是不发"，与权限故障一模一样，最难误诊（ADR-042 已为同类问题付过学费）。
     */
    Set<String> rolesHolding(String permissionCode);

    /**
     * 持有某角色的在职员工 userId 去重集合，<b>不带门店过滤</b>（ADR-047：平台管理员的门店消息全覆盖）。
     *
     * <p>JOIN 链与 {@link #holdsGlobalRole} 逐条同构（在职 ∧ 任意 ACTIVE 归属 ∧ 该角色），因此两者对
     * "谁算持有者"的判断必然一致——一个说算、一个说不算，收件人就会漏。
     *
     * <p>它只决定<b>谁收到一条告知</b>，不授予任何权限；鉴权仍只走 {@link #storePermissions}
     * 与 PermissionAssembler 的平台管理员分支（"权限来自该店归属"这条地基不从这边绕过去）。
     */
    List<Long> userIdsWithRole(String roleCode);
}
