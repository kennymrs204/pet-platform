package com.petplatform.boarding.api;

import com.petplatform.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 托管域错误码（第八阶段 §2-2）。50003 数值属预约段（"非法状态流转"），
 * 入住单状态机与其语义同源，经 ADR-015 §8 裁决共用数值、各模块各自定义枚举。
 */
@Getter
@RequiredArgsConstructor
public enum BoardingErrorCode implements ErrorCode {

    ILLEGAL_STATE_TRANSITION(50003, "非法状态流转"),
    ONLY_ASSIGNED_CARETAKER(70001, "仅任务执行人可操作");

    private final int code;
    private final String message;
}
