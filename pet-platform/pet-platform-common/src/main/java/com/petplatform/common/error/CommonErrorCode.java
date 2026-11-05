package com.petplatform.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 通用错误码（10000 段）。业务分段错误码见第八阶段 API 设计 §2-2，
 * 由各业务模块在自己的 api 包中定义并实现 ErrorCode。
 */
@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    SUCCESS(0, "成功"),
    PARAM_INVALID(10001, "参数校验失败"),
    UNAUTHORIZED(10002, "未登录或登录已过期"),
    FORBIDDEN(10003, "无权限"),
    NOT_FOUND(10004, "资源不存在"),
    DUPLICATE_REQUEST(10005, "重复提交"),
    ILLEGAL_STATE_TRANSITION(10006, "非法状态流转"),
    SYSTEM_ERROR(10009, "系统繁忙，请稍后重试");

    private final int code;
    private final String message;
}
