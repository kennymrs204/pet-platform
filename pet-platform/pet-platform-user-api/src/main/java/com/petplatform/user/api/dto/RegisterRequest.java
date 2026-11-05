package com.petplatform.user.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 验证码注册（ADR-009：注册/登录分离）。密码规则：8-20 位，须含字母和数字。
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "手机号必填")
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "验证码必填")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为6位数字")
    private String code;

    @NotBlank(message = "密码必填")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)\\S{8,20}$", message = "密码须为8-20位且包含字母和数字")
    private String password;
}
