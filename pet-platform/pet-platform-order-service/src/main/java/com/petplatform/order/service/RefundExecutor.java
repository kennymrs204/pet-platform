package com.petplatform.order.service;

import com.petplatform.order.api.dto.RefundView;

/**
 * 退款执行缝隙（ADR-043 §4）。<b>存在的唯一理由</b>：让"审批通过"和"退款成功"是两个状态、两件事，
 * 使"预约落 CANCELLED / 订单转 REFUNDED / 发 REFUND_SUCCESS 消息"这三样必须排在执行成功之后。
 *
 * <p>失败约定：<b>抛异常</b>，不返回 false——抛在本事务里，整笔审批（含 APPLYING→APPROVED）一起回滚，
 * 于是不会出现"退款单停在 APPROVED 却无人能再推它"的死账。接真实渠道时这条约定不变：
 * 渠道受理失败/超时 ⇒ 审批不生效，前台重试即可。
 */
public interface RefundExecutor {

    /** 执行一笔已审批（APPROVED）的退款；成功才允许后续落定动作。 */
    void execute(RefundView refund);
}
