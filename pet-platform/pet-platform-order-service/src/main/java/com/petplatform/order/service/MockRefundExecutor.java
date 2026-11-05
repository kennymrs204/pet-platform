package com.petplatform.order.service;

import com.petplatform.order.api.dto.RefundView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Mock 退款执行（ADR-043 §4）。当前项目仍在 Mock Payment / Mock Refund 阶段：
 * 这里<b>什么都不做就报成功</b>——不碰渠道、不写 {@code pay_refund_txn}、不调 payment 模块。
 *
 * <p>为什么要专门留一个"执行步"而不是把 APPROVED→SUCCESS 合成一步：
 * 合成一步就没法在代码里表达"钱先退成功、订单才取消"这条业务顺序，
 * 而这条顺序正是本次要纠正的东西（见 ADR-043 根因）。接真实渠道时换掉本实现即可，
 * 状态机与消息触发点都不用改。
 */
@Slf4j
@Component
public class MockRefundExecutor implements RefundExecutor {

    @Override
    public void execute(RefundView refund) {
        log.info("模拟退款执行成功 refundId={} orderId={} amount={}（Mock：未调用真实渠道，未写 pay_refund_txn）",
                refund.getId(), refund.getOrderId(), refund.getAmount());
    }
}
