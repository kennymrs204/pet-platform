package com.petplatform.app.web;

import com.petplatform.app.security.PermissionAssembler;
import com.petplatform.common.result.Result;
import com.petplatform.employee.api.EmployeeManageApi;
import com.petplatform.employee.api.dto.EmployeeManageView;
import com.petplatform.employee.api.dto.EmployeeStatusUpdateCommand;
import com.petplatform.user.api.UserSessionApi;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台侧变更员工全局状态（ADR-055，兑现 ADR-050 "停用需联动踢会话，另裁决"）。
 *
 * <p>闭环的三块必须在同一笔里：① employee 写 status（服务层 + 最后一名店长守卫）；
 * ② {@link PermissionAssembler#evictAll}——所有权限 SQL 本来就要求 {@code e.status='ACTIVE'}，
 * evict 让"下次请求重建"立刻拿到空集，B 端功能当场全灭（不清就要等 access TTL 2h）；
 * ③ {@link UserSessionApi#revokeAllSessions}——清 refresh 白名单，登录态随 access 自然过期死亡。
 * ②③ 放控制器层的理由与 {@code AdminEmployeeRoleController} 的 evict 编排同构：装配点在 app
 * （user/employee 模块按 §3.2 矩阵不能反向依赖），且本方法返回即事务已提交（§1.2 接线前提）。
 *
 * <p>刻意不禁登录本身：usr_user 与员工档案是两码事，同一账号可能是 C 端顾客（ADR-027）；
 * "进不去 B 端"由 identities 空集（ADR-012 只认 ACTIVE）+ 全端点 10003 自动达成。
 * 闸 {@code store:manage}（仅 ADMIN）；不吃 storeId——状态是全局的（与 profile 端点同款口径）。
 */
@RestController
@RequestMapping("/api/v1/admin/employees")
public class AdminEmployeeStatusController {

    private final EmployeeManageApi manageApi;
    private final PermissionAssembler permissionAssembler;
    private final UserSessionApi userSessionApi;

    public AdminEmployeeStatusController(EmployeeManageApi manageApi,
                                         PermissionAssembler permissionAssembler,
                                         UserSessionApi userSessionApi) {
        this.manageApi = manageApi;
        this.permissionAssembler = permissionAssembler;
        this.userSessionApi = userSessionApi;
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('perm:store:manage')")
    public Result<EmployeeManageView> updateStatus(@PathVariable Long id,
                                                   @Valid @RequestBody EmployeeStatusUpdateCommand command) {
        EmployeeManageView view = manageApi.updateStatus(id, command);
        if (view != null && view.getUserId() != null) {
            permissionAssembler.evictAll(view.getUserId());
            userSessionApi.revokeAllSessions(view.getUserId());
        }
        return Result.ok(view);
    }
}
