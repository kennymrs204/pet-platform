package com.petplatform.pet.api;

import com.petplatform.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 宠物域错误码（第八阶段 §2-2 用户/宠物段 20000，ADR-014）。
 * 20102（宠物状态不可预约）由预约段落地时再定义，禁止提前占用。
 */
@Getter
@RequiredArgsConstructor
public enum PetErrorCode implements ErrorCode {

    PET_FORBIDDEN(20101, "无权操作该宠物");

    private final int code;
    private final String message;
}
