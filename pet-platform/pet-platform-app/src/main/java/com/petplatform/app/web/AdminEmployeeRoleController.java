package com.petplatform.app.web;

import com.petplatform.app.security.PermissionAssembler;
import com.petplatform.common.result.Result;
import com.petplatform.employee.api.EmployeeManageApi;
import com.petplatform.employee.api.dto.AssignRequest;
import com.petplatform.employee.api.dto.EmployeeManageView;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台侧授角色（ADR-033 补口，P8 §4.3 ADMIN 段 {@code POST /admin/employees} 的 roles 半边）。
 *
 * <p>为什么必须有这条：ADR-001-E 把 STORE_MANAGER 的授予权**只**留给 ADMIN，而 ADMIN 账号归属总部虚拟店
 * 且前端 adminOnly 不可切普通店 → 没有这个入口，"给某店任命店长"在系统里无路可走，只能改库。
 *
 * <p>与店长端的三处不同：① 目标门店由 {@code storeId} 显式给出（ADMIN 的请求上下文是总部店，
 * 不能拿 X-Store-Id 当授权主语）；② 白名单放开到 {@link EmployeeManageApi#PLATFORM_ASSIGNABLE_ROLES}
 * （即额外允许 STORE_MANAGER）；③ 闸是 {@code store:manage}——V2 种子实测该码只有 ADMIN 持有，
 * 店长没有，所以店长无法绕道本端点自我复制。CUSTOMER/ADMIN 两个平台维度角色仍不可授：
 * 前者不是员工角色，后者属平台账号段（本期不开放写侧）。
 *
 * <p>调用方式与 {@code /admin/services} 同约定：带 {@code X-Store-Id: 1}（总部店）以拿到特权码。
 */
@RestController
@RequestMapping("/api/v1/admin/employees")
public class AdminEmployeeRoleController {

    private final EmployeeManageApi manageApi;
    private final PermissionAssembler permissionAssembler;

    public AdminEmployeeRoleController(EmployeeManageApi manageApi, PermissionAssembler permissionAssembler) {
        this.manageApi = manageApi;
        this.permissionAssembler = permissionAssembler;
    }

    /** 全量替换该员工在指定门店的角色集合；该员工必须在目标门店有 ACTIVE 归属（服务层强制，跨店 10004）。 */
    @PostMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('perm:store:manage')")
    public Result<EmployeeManageView> replaceRoles(@PathVariable Long id,
                                                   @RequestParam(required = false) Long storeId,
                                                   @Valid @RequestBody AssignRequest request) {
        // 缺参必须是 10001 而不是 10009：required=true 会让 Spring 抛 MissingServletRequestParameterException，
        // 落到兜底 handler 变成"系统繁忙"——调用方看不出是自己漏传了 storeId。
        if (storeId == null || storeId == 0L) {
            throw new com.petplatform.common.error.BusinessException(
                    com.petplatform.common.error.CommonErrorCode.PARAM_INVALID, "storeId 必填（平台侧按店授权）");
        }
        EmployeeManageView view =
                manageApi.replaceRoles(storeId, id, request.getCodes(), EmployeeManageApi.PLATFORM_ASSIGNABLE_ROLES);
        // 授权变更即刻生效，否则对方要等 access token 过期（2h）才拿到新权限码
        if (view != null && view.getUserId() != null) {
            permissionAssembler.evict(view.getUserId(), storeId);
        }
        return Result.ok(view);
    }
}
