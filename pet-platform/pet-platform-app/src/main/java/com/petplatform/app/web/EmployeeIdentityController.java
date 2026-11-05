package com.petplatform.app.web;

import com.petplatform.common.result.Result;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.employee.api.dto.EmployeeIdentityView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * B 端员工身份查询（ADR-012，GET /api/v1/auth/employee-identities）。
 *
 * 鉴权口径：仅登录态（SecurityConfig 对本路径单独 authenticated()，先例 /auth/password/change）。
 * 评审稿曾建议 @PreAuthorize('perm:emp:self:view')，但实际代码中 emp:self:view 只在
 * storeCtx>0 时装配（PermissionAssembler），而本端点正是 storeCtx 的数据来源——方法级挂码会
 * 形成鸡生蛋死锁，且 CUSTOMER 种子无该码将使"无身份返回 null 信封"不可达，故按实际代码定案。
 * emp:self:view 的 SELF 数据范围语义在数据层兑现：只查 CurrentUserHolder 本人 userId。
 *
 * 端点放 app 装配层的原因：user 模块按 §3.2 不依赖 employee，
 * app 是唯一可同时消费两模块 api 的组合根（PermissionAssembler 先例）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class EmployeeIdentityController {

    private final EmployeeQueryApi employeeQueryApi;
    private final com.petplatform.store.api.StoreQueryApi storeQueryApi;

    public EmployeeIdentityController(EmployeeQueryApi employeeQueryApi,
                                      com.petplatform.store.api.StoreQueryApi storeQueryApi) {
        this.employeeQueryApi = employeeQueryApi;
        this.storeQueryApi = storeQueryApi;
    }

    @GetMapping("/employee-identities")
    public Result<EmployeeIdentityView> identities() {
        Long userId = CurrentUserHolder.require().userId();
        EmployeeIdentityView view = employeeQueryApi.activeIdentityOf(userId);
        if (view == null) {
            view = EmployeeIdentityView.builder().stores(List.of()).build();
        }
        // ADR-012 遗留回补（ADR-022 §5）：storeName 占位由 store-api 批量解析，employee 模块不碰 sto_store
        if (!view.getStores().isEmpty()) {
            Map<Long, String> names = storeQueryApi.storeNames(view.getStores().stream()
                    .map(com.petplatform.employee.api.dto.StoreIdentityView::getStoreId).toList());
            view.getStores().forEach(s -> s.setStoreName(names.get(s.getStoreId())));
        }
        return Result.ok(view);
    }
}
