package com.petplatform.schedule.api;

import com.petplatform.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 排班/资源域错误码（第八阶段 §2-2 40000 段）。
 * 72001 数值属履约/托管段（§2-2 定义"笼位迁移冲突"），由占用表 Owner=schedule 的实现抛出，
 * 故定义在本模块 api（先例：20101 定义于 pet-api，ADR-015 §8）。
 */
@Getter
@RequiredArgsConstructor
public enum ResourceErrorCode implements ErrorCode {

    KENNEL_CAPACITY_INSUFFICIENT(40005, "笼位容量不足"),
    RESOURCE_FULL(40004, "该时段资源已约满"),
    RESOURCE_IN_USE(40006, "资源存在当前或未来占用，暂不可停用或设为维护中"),
    /** 锁内唯一索引冲突的友好映射（§3.1"提示重选时段"），数值属预约段，先例同 72001（ADR-016 §5）。 */
    SLOT_TAKEN(50001, "时段已被占用，请重选时段"),
    KENNEL_MOVE_CONFLICT(72001, "笼位迁移冲突");

    private final int code;
    private final String message;
}
