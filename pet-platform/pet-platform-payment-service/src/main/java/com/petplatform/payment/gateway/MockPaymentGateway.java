package com.petplatform.payment.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 模拟支付通道（ADR-016 §8）：仅当 pet.payment.channel=mock 注册——本地/测试/验收环境专用；
 * 生产 profile 不配置该值 ⇒ 本 Bean 与 MockPaymentController 均不存在（404），
 * 不存在"任意订单标记已付"的生产入口。回调=POST /api/v1/payment/mock/{paymentNo}/notify。
 */
@Component
@ConditionalOnProperty(name = "pet.payment.channel", havingValue = "mock")
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public String channel() {
        return "MOCK";
    }

    @Override
    public Map<String, String> prepay(String paymentNo, long amountFen, String subject) {
        return Map.of("mock", "true", "paymentNo", paymentNo, "amount", String.valueOf(amountFen));
    }

    /** mock"验签"：rawBody 即 paymentNo（模拟 notify 已鉴权）。 */
    @Override
    public String[] verifyAndParse(String rawBody) {
        String paymentNo = rawBody == null ? "" : rawBody.trim();
        if (paymentNo.isEmpty()) {
            throw new IllegalArgumentException("mock notify 缺少 paymentNo");
        }
        return new String[]{paymentNo, "MOCK-" + paymentNo, null};
    }

    @Override
    public String queryStatus(String paymentNo) {
        return "PROCESSING"; // mock 通道无独立渠道侧状态，以本地流水为准
    }

    @Override
    public String ackSuccess() {
        return "OK";
    }

    @Override
    public String ackFail(String message) {
        return "FAIL:" + message;
    }
}
