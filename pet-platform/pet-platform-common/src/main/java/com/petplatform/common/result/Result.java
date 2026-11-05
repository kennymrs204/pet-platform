package com.petplatform.common.result;

import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.error.ErrorCode;
import lombok.Data;

/**
 * 统一响应结构（ADR-008-5）。业务失败也返回 HTTP 200 + 非零 code；
 * 支付回调端点是唯一例外（按渠道规范应答，不套本结构）。
 */
@Data
public class Result<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = CommonErrorCode.SUCCESS.getCode();
        r.message = CommonErrorCode.SUCCESS.getMessage();
        r.data = data;
        return r;
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        Result<T> r = new Result<>();
        r.code = errorCode.getCode();
        r.message = errorCode.getMessage();
        return r;
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        Result<T> r = new Result<>();
        r.code = errorCode.getCode();
        r.message = message;
        return r;
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
