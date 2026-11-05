package com.petplatform.employee.api;

import com.petplatform.employee.api.dto.EmployeeManageView;
import com.petplatform.employee.api.dto.OnboardEmployeeCommand;

import java.util.List;
import java.util.Set;

/**
 * 员工写侧端口（ADR-033，兑现 ADR-001-E 与 P8 §4.3 规划的 {@code /store/employees} 写侧）。
 *
 * <p>授权主语一律是**门店上下文**：所有方法都收 storeId（由控制器的 X-Store-Id 权威给出，
 * 前端传的门店 id 从不构成授权），且目标员工必须在**本店有 ACTIVE 归属**，否则 10004（跨店与不存在同码防探测）。
 *
 * <p>刻意不提供"改全局资料"（姓名/工号/status）：{@code emp_employee} 是跨店共享的一行，
 * 两个店长互改必然打架，那属平台 ADMIN 段（ADR-050 档案 / ADR-055 状态已从平台侧提供）。
 * 店长能做的店级动作只有：纳编、授本店角色、授技能、结束本店归属。
 */
public interface EmployeeManageApi {

    /**
     * 员工型角色（店长可授的 5 个）。ADR-001-E：店长可分配**除 STORE_MANAGER 外**的员工型角色，
     * 理由=防提权链（店长自我复制）。CUSTOMER/ADMIN 是平台维度角色，任何门店端点都不授。
     */
    Set<String> STORE_ASSIGNABLE_ROLES =
            Set.of("DOCTOR", "GROOMER", "DOG_WALKER", "CARETAKER", "RECEPTIONIST");

    /** 平台侧可授集 = 员工型 + STORE_MANAGER（仅 ADMIN 端点，闸 {@code store:manage}，P8 §4.3 ADMIN 段）。 */
    Set<String> PLATFORM_ASSIGNABLE_ROLES =
            Set.of("DOCTOR", "GROOMER", "DOG_WALKER", "CARETAKER", "RECEPTIONIST", "STORE_MANAGER");

    /**
     * 纳编：手机号必须已注册（未注册 10004 并引导先注册）；已建档者只加本店归属与角色，
     * **不覆盖** 库里已有的姓名/工号。幂等：同一 (手机,店) 重复调用不产生第二行。
     */
    EmployeeManageView onboard(Long storeId, OnboardEmployeeCommand cmd);

    /**
     * 全量替换**本店**角色（空列表=清空）。
     *
     * @param callerRoles 调用方可授白名单：店长传 {@link #STORE_ASSIGNABLE_ROLES}，ADMIN 端点传
     *                    {@link #PLATFORM_ASSIGNABLE_ROLES}。白名单外一律 10001，STORE_MANAGER 由店长传则 10003
     */
    EmployeeManageView replaceRoles(Long storeId, Long employeeId, List<String> roleCodes, Set<String> callerRoles);

    /** 全量替换技能（技能是员工全局属性，故只在"本店可管理"这一闸下生效）。 */
    EmployeeManageView replaceSkills(Long storeId, Long employeeId, List<String> skillCodes);

    /** 结束本店归属（ACTIVE→ENDED）；若被结束的是主店且他店仍在职，把主店确定性转移过去。 */
    EmployeeManageView endAffiliation(Long storeId, Long employeeId);

    /**
     * 平台侧编辑全局档案（ADR-050，兑现本接口头注里"刻意不提供"留给平台段的那半句）：
     * 姓名/工号/入职日是跨店共享的一行，唯一写权在平台段（闸 {@code store:manage}，控制器层把关）。
     * 这是全仓唯一一个**不吃门店上下文**的员工写方法——授权主语是平台身份本身，不是任何一家店。
     * 提交语义 PUT 全量：employeeNo/hiredAt 传 null=清空。刻意不含 status（停用需联动踢会话，另裁决）——
     * 该裁决已由 ADR-055 的 {@link #updateStatus} 落地。
     */
    EmployeeManageView updateProfile(Long employeeId, com.petplatform.employee.api.dto.EmployeeProfileUpdateCommand cmd);

    /**
     * 平台侧调整技能（ADR-051，补 ADR-040 留下的写权悬空口子）：技能是全局属性、只有主店店长可写，
     * 但**主店可能是没有店长的总部虚拟店**（实测：员工主店=id=1，全店仅 ADMIN 角色无人持
     * {@code emp:skill:assign} 的店长语义）——此时全系统无人能改这名员工的技能。
     * 平台通道的语义：主店非空 → 以主店为语境复用 {@link #replaceSkills}（ADR-040 守卫天然通过）；
     * 主店为空 → fail-open 直接替换（同 ADR-040 §3，宁可平台可写也不制造死数据）。
     * 闸 {@code store:manage}（控制器层），技能不进权限码集故无需 evict。
     */
    EmployeeManageView adminReplaceSkills(Long employeeId, List<String> skillCodes);

    /**
     * 平台侧变更员工全局状态（ADR-055）：ACTIVE(恢复) / SUSPENDED(停职) / RESIGNED(离职)，同值幂等。
     * 授权主语是平台身份本身（闸 {@code store:manage}，控制器层），不吃门店上下文——状态在
     * {@code emp_employee} 这一跨店共享行上，与 {@link #updateProfile} 同款口径。
     *
     * <p>离开 ACTIVE 前对该员工每个"能干活的店长店"跑 ADR-035 同一条不变量（操作后仍需剩 ≥1 名
     * 能干活的店长）；权限缓存全上下文失效（{@code PermissionAssembler.evictAll}）与会话撤销
     * （清 refresh 白名单）刻意不在这里——装配点在 app、撤销端口在 user-api，由控制器层在
     * 同一事务提交后编排（§1.2 接线条款）。
     */
    EmployeeManageView updateStatus(Long employeeId,
                                    com.petplatform.employee.api.dto.EmployeeStatusUpdateCommand cmd);
}
