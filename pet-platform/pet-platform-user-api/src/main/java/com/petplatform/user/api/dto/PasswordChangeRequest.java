package com.petplatform.user.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 登录态改密（ADR-011）：旧密码错 → 20004；新密码规则同 ADR-009。
 */
@Data
public class PasswordChangeRequest {

    @NotBlank(message = "原密码必填")
    private String oldPassword;

    @NotBlank(message = "新密码必填")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)\\S{8,20}$", message = "密码须为8-20位且包含字母和数字")
    private String newPassword;
}
