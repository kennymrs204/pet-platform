package com.petplatform.payment.web;

import com.petplatform.payment.api.PaymentCommandApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟支付确认端点（ADR-016 §8）：仅 pet.payment.channel=mock 注册；
 * 生产不配置该值 ⇒ 端点不存在（404），无"任意订单置已付"入口。
 */
@RestController
@RequestMapping("/api/v1/payment/mock")
@ConditionalOnProperty(name = "pet.payment.channel", havingValue = "mock")
public class MockPaymentController {

    private final PaymentCommandApi paymentCommandApi;

    public MockPaymentController(PaymentCommandApi paymentCommandApi) {
        this.paymentCommandApi = paymentCommandApi;
    }

    @PostMapping(value = "/{paymentNo}/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    public String notifyPaid(@PathVariable String paymentNo) {
        return paymentCommandApi.handleCallback("MOCK", paymentNo);
    }
}
