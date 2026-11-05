package com.petplatform.user.web;

import com.petplatform.common.result.Result;
import com.petplatform.user.api.dto.LoginRequest;
import com.petplatform.user.api.dto.PasswordChangeRequest;
import com.petplatform.user.api.dto.PasswordLoginRequest;
import com.petplatform.user.api.dto.PasswordResetRequest;
import com.petplatform.user.api.dto.RefreshRequest;
import com.petplatform.user.api.dto.RegisterRequest;
import com.petplatform.user.api.dto.TokenResponse;
import com.petplatform.user.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证端点（ADR-009：注册/登录分离；ADR-011：+密码找回/修改）。register/login/login/refresh/password/reset 公开
 * （/api/v1/auth/** permitAll 已配），logout 与 password/change 需登录态（change 在 SecurityConfig 单独例外）。
 * logout 直接从 Authorization 头取 jti，不依赖 app 模块的 SecurityContext 类型
 * （user 模块禁止反向依赖 app，ArchUnit 固化）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return Result.ok();
    }

    @PostMapping("/login")
    public Result<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    @PostMapping("/login/password")
    public Result<TokenResponse> loginByPassword(@Valid @RequestBody PasswordLoginRequest request) {
        return Result.ok(authService.loginByPassword(request));
    }

    @PostMapping("/password/reset")
    public Result<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        authService.resetPassword(request);
        return Result.ok();
    }

    /** 登录态端点：SecurityConfig 对 /api/v1/auth/** 放行清单中的本路径单独要求已认证（ADR-011）。 */
    @PostMapping("/password/change")
    public Result<Void> changePassword(@Valid @RequestBody PasswordChangeRequest request) {
        authService.changePassword(request);
        return Result.ok();
    }

    @PostMapping("/refresh")
    public Result<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return Result.ok(authService.refresh(request.getRefreshToken()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("Authorization") String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            authService.logout(authorization.substring(7).trim());
        }
        return Result.ok();
    }
}
