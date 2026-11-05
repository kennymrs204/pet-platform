package com.petplatform.user.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 密码登录（ADR-009）。账号不存在与密码错误统一返回 20002，防账号枚举。
 */
@Data
public class PasswordLoginRequest {

    @NotBlank(message = "手机号必填")
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "密码必填")
    private String password;
}
