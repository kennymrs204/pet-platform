package com.petplatform.user.api;

import com.petplatform.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 认证段 11000 + 用户段 20000（第八阶段 §2-2 错误码分段，禁止发明段外码）。
 */
@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {

    SMS_CODE_INVALID(11001, "验证码错误或已失效"),
    TOKEN_EXPIRED(11002, "token 已过期"),
    TOKEN_INVALID(11003, "token 无效"),
    REFRESH_INVALID(11004, "refresh token 已失效，请重新登录"),
    ACCOUNT_DISABLED(11005, "账号已封禁"),
    PHONE_REGISTERED(20001, "手机号已注册"),
    PASSWORD_INCORRECT(20002, "手机号或密码错误"),
    PHONE_NOT_REGISTERED(20003, "手机号未注册，请先注册"),
    OLD_PASSWORD_INCORRECT(20004, "原密码错误");

    private final int code;
    private final String message;
}
