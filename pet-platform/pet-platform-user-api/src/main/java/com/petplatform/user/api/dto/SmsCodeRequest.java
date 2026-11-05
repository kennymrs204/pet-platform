package com.petplatform.user.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 验证码发送（ADR-011：场景隔离）。scene 缺省 LOGIN（旧调用兼容），
 * 枚举 LOGIN/REGISTER/RESET，非法值由 @Pattern 拦成 10001。
 */
@Data
public class SmsCodeRequest {

    @NotBlank(message = "手机号必填")
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    private String phone;

    @Pattern(regexp = "^(LOGIN|REGISTER|RESET)$", message = "验证码场景不合法")
    private String scene;
}
