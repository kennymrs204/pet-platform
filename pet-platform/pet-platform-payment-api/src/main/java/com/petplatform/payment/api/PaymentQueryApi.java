package com.petplatform.payment.api;

import com.petplatform.payment.api.dto.OfflinePaymentView;

/**
 * 支付查询端口（表 Owner：payment，pay_payment）。当前只服务 B 端订单详情的
 * 线下收款状态展示（ADR-006 §6）：登记待复核的单要能让前台看到"待他人复核"。
 */
public interface PaymentQueryApi {

    /**
     * 该订单最新一笔 OFFLINE 流水（含 PROCESSING/SUCCESS）；从无线下登记返回 null。
     * 不含线上渠道流水——支付方式展示走订单行自身 paymentMethod。
     */
    OfflinePaymentView latestOfflineByOrder(Long orderId);
}
