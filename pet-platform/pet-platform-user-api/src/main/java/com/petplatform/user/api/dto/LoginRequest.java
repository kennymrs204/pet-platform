package com.petplatform.user.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 验证码登录（ADR-009：注册/登录分离后不再自动建号，
 * 未注册手机号返回 20003 引导去注册）。
 */
@Data
public class LoginRequest {

    @NotBlank(message = "手机号必填")
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "验证码必填")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为6位数字")
    private String code;
}
