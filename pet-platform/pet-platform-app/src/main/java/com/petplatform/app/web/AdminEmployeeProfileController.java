package com.petplatform.app.web;

import com.petplatform.common.result.Result;
import com.petplatform.employee.api.EmployeeManageApi;
import com.petplatform.employee.api.dto.EmployeeManageView;
import com.petplatform.employee.api.dto.EmployeeProfileUpdateCommand;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台侧编辑员工全局档案（ADR-050，兑现 ADR-033 §2 留给平台段的"改全局资料"半句）。
 *
 * <p>为什么店长端点不能做：{@code emp_employee} 是跨店共享的一行，姓名/工号/入职日改了**所有门店**
 * 看到的都是新值——两个店长互改必然互相吞掉（ADR-033 §2 的原话）。所以唯一写权收归平台段：
 * 闸 {@code store:manage}（V2 种子实测仅 ADMIN 持有），与 {@link AdminEmployeeRoleController} 同一家族。
 *
 * <p>与 {@code /admin/employees/{id}/roles} 的两处不同：① **不吃 storeId**——档案是全局的，
 * 已离职（无 ACTIVE 归属）者的姓名同样只有平台能改，按店收权反而会把这批人锁死；
 * ② 不调 {@code PermissionAssembler.evict}——姓名/工号/入职日不在权限码集里，改它们不改变任何鉴权结果。
 *
 * <p>刻意不做 status（在职/离职）：停用要联动清 refresh 白名单踢下线（revokeAll），是平台停用端点的
 * 独立裁决（ADR-033 遗留）——**已于 ADR-055 另立 {@code PUT /admin/employees/{id}/status} 落地**，
 * 不搭本端点的车。调用方式与 {@code /admin/services} 同约定：
 * ADMIN 可带 {@code X-Store-Id: 1}（总部店）或经 ADR-044 代入门店后的上下文均可——本端点不读它。
 */
@RestController
@RequestMapping("/api/v1/admin/employees")
public class AdminEmployeeProfileController {

    private final EmployeeManageApi manageApi;

    public AdminEmployeeProfileController(EmployeeManageApi manageApi) {
        this.manageApi = manageApi;
    }

    @PutMapping("/{id}/profile")
    @PreAuthorize("hasAuthority('perm:store:manage')")
    public Result<EmployeeManageView> updateProfile(@PathVariable Long id,
                                                    @Valid @RequestBody EmployeeProfileUpdateCommand command) {
        return Result.ok(manageApi.updateProfile(id, command));
    }
}
