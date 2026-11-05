package com.petplatform.payment.web;

import com.petplatform.payment.api.PaymentCommandApi;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付回调（第八阶段 §3.2）：permitAll（SecurityConfig /api/v1/payment/callback/** 已放行），
 * 验签即鉴权；应答体按渠道规范原样输出——全局唯一不套 Result<T> 的端点（§3.2 原文，
 * 本类即"独立于规范之外"的声明位）。异常抛出 → 由全局兜底 5xx → 渠道按策略重试。
 */
@RestController
@RequestMapping("/api/v1/payment/callback")
public class PaymentCallbackController {

    private final PaymentCommandApi paymentCommandApi;

    public PaymentCallbackController(PaymentCommandApi paymentCommandApi) {
        this.paymentCommandApi = paymentCommandApi;
    }

    @PostMapping(value = "/{channel}", consumes = MediaType.ALL_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public String callback(@PathVariable String channel, @RequestBody String rawBody) {
        return paymentCommandApi.handleCallback(channel, rawBody);
    }
}
