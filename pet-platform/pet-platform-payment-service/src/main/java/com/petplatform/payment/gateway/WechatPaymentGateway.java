package com.petplatform.payment.gateway;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 微信支付 JSAPI 通道占位（ADR-006-7：沙箱联调通过才允许生产配置；密钥环境变量注入）。
 * pet.payment.channel=wechat 时注册；真实对接为遗留项——prepay/query 显式抛 10009，
 * 绝不静默假成功（TODO：微信 v3 SDK 下单/回调解密 AEAD_AES_256_GCM/平台证书验签）。
 */
@Component
@ConditionalOnProperty(name = "pet.payment.channel", havingValue = "wechat")
public class WechatPaymentGateway implements PaymentGateway {

    @Override
    public String channel() {
        return "WECHAT";
    }

    @Override
    public Map<String, String> prepay(String paymentNo, long amountFen, String subject) {
        throw new BusinessException(CommonErrorCode.SYSTEM_ERROR, "微信支付通道未接入（沙箱联调后启用）");
    }

    @Override
    public String[] verifyAndParse(String rawBody) {
        throw new BusinessException(CommonErrorCode.SYSTEM_ERROR, "微信支付通道未接入");
    }

    @Override
    public String queryStatus(String paymentNo) {
        throw new BusinessException(CommonErrorCode.SYSTEM_ERROR, "微信支付通道未接入");
    }

    @Override
    public String ackSuccess() {
        return "{\"code\":\"SUCCESS\",\"message\":\"成功\"}";
    }

    @Override
    public String ackFail(String message) {
        return "{\"code\":\"FAIL\",\"message\":\"" + message + "\"}";
    }
}
