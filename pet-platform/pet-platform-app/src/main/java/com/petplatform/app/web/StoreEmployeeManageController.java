package com.petplatform.app.web;

import com.petplatform.app.security.PermissionAssembler;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.employee.api.EmployeeManageApi;
import com.petplatform.employee.api.dto.AssignRequest;
import com.petplatform.employee.api.dto.EmployeeManageView;
import com.petplatform.employee.api.dto.OnboardEmployeeCommand;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端员工写侧（ADR-033，兑现 ADR-001-E 与 P8 §4.3）。控制器放 app 层只有一个理由：
 * 写完之后必须 {@link PermissionAssembler#evict} —— 装配点在 app，employee 模块按矩阵不能反向依赖它
 * （{@code PermissionAssembler} 的接线条款原文：员工写侧落地时必须在事务提交后调用）。
 *
 * <p>店长一律只能授 {@link EmployeeManageApi#STORE_ASSIGNABLE_ROLES} 五个员工型角色；
 * STORE_MANAGER 由 ADMIN 经 {@code /admin/employees/{id}/roles} 授（防提权链，ADR-001-E）。
 */
@RestController
@RequestMapping("/api/v1/store/employees")
public class StoreEmployeeManageController {

    private final EmployeeManageApi manageApi;
    private final PermissionAssembler permissionAssembler;

    public StoreEmployeeManageController(EmployeeManageApi manageApi, PermissionAssembler permissionAssembler) {
        this.manageApi = manageApi;
        this.permissionAssembler = permissionAssembler;
    }

    /** 纳编：手机号须已注册；已建档者只加本店归属与角色，不动他的全局姓名/工号。 */
    @PostMapping
    @PreAuthorize("hasAuthority('perm:emp:create')")
    public Result<EmployeeManageView> onboard(@Valid @RequestBody OnboardEmployeeCommand command) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        EmployeeManageView view = manageApi.onboard(storeId, command);
        evict(view, storeId);
        return Result.ok(view);
    }

    /** 全量替换本店角色（空数组=清空）。 */
    @PostMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('perm:emp:role:assign')")
    public Result<EmployeeManageView> replaceRoles(@PathVariable Long id, @Valid @RequestBody AssignRequest request) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        EmployeeManageView view =
                manageApi.replaceRoles(storeId, id, request.getCodes(), EmployeeManageApi.STORE_ASSIGNABLE_ROLES);
        evict(view, storeId);
        return Result.ok(view);
    }

    /** 全量替换技能（技能有真实消费方：availability 的员工候选要技能超集，ADR-016 §7）。 */
    @PutMapping("/{id}/skills")
    @PreAuthorize("hasAuthority('perm:emp:skill:assign')")
    public Result<EmployeeManageView> replaceSkills(@PathVariable Long id, @Valid @RequestBody AssignRequest request) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        EmployeeManageView view = manageApi.replaceSkills(storeId, id, request.getCodes());
        evict(view, storeId);
        return Result.ok(view);
    }

    /** 结束本店归属（店级动作；全局在职状态不在此处，那属平台段）。 */
    @PostMapping("/{id}/leave-store")
    @PreAuthorize("hasAuthority('perm:emp:update')")
    public Result<EmployeeManageView> leaveStore(@PathVariable Long id) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        EmployeeManageView view = manageApi.endAffiliation(storeId, id);
        evict(view, storeId);
        return Result.ok(view);
    }

    /**
     * 权限缓存即时失效：不 evict，"授完角色本人却仍拿旧权限码"只能等 access TTL（2h）自然过期。
     * 刻意不发 EmployeeRoleChangedEvent——除本处外无第二个消费者，发事件等于造孤儿。
     */
    private void evict(EmployeeManageView view, Long storeId) {
        if (view != null && view.getUserId() != null) {
            permissionAssembler.evict(view.getUserId(), storeId);
        }
    }

    private Long requireStoreCtx(AuthPrincipal principal) {
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }
}
