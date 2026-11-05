package com.petplatform.order.api;

import com.petplatform.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 订单域错误码（第八阶段 §2-2 订单/支付段 60000，ADR-016）。
 * 退款段 60003~60006 随退款链落地定义（ADR-023 申请段 + ADR-037 审批段），禁止提前占用其余值。
 */
@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {

    NOT_PAYABLE(60001, "订单状态不可支付"),
    AMOUNT_MISMATCH(60002, "金额不一致"),
    /** 第六阶段 §7 退款链落地（ADR-023）：条件更新 0 行=并发态漂移，整笔取消回滚 */
    ORDER_STATE_CONFLICT(60003, "订单状态已变化，请刷新后重试"),
    /** 第六阶段 §7 :125 Σ退款≤实付 */
    REFUND_EXCEEDED(60004, "退款金额超过实付"),
    /** 审批时退款单不存在/已被别人处理（ADR-037 §4：条件更新 0 行） */
    REFUND_NOT_APPROVABLE(60005, "退款单状态已变化，请刷新后重试"),
    /** 超出店长可批上限（sys_config 507，ADR-001-B 分级审批）：不是无权限，是额度不够 */
    REFUND_LIMIT_FOR_MANAGER(60006, "退款金额超出店长可批上限，需平台管理员审批");

    private final int code;
    private final String message;
}
