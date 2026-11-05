package com.petplatform.user.web;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.user.api.dto.CustomerProfileView;
import com.petplatform.user.api.dto.ProfileUpdateRequest;
import com.petplatform.user.service.CustomerProfileService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端"我的"账号资料（P8 §4.2 GET/PUT /customer/profile，ADR-027）。
 * 权限码 account:profile:manage=全角色通用码（第一阶段 §4.1，PermissionAssembler 恒授），
 * 新注册用户无 CustomerProfile 也可管理本人资料；userId 只取认证上下文，无入参面。
 */
@RestController
@RequestMapping("/api/v1/customer/profile")
public class CustomerProfileController {

    private final CustomerProfileService profileService;

    public CustomerProfileController(CustomerProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('perm:account:profile:manage')")
    public Result<CustomerProfileView> me() {
        CustomerProfileView view = profileService.get(CurrentUserHolder.require().userId());
        if (view == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        return Result.ok(view);
    }

    @PutMapping
    @PreAuthorize("hasAuthority('perm:account:profile:manage')")
    public Result<CustomerProfileView> update(@Valid @RequestBody ProfileUpdateRequest request) {
        CustomerProfileView view = profileService.update(CurrentUserHolder.require().userId(), request);
        if (view == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        return Result.ok(view);
    }
}
