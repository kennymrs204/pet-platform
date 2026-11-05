package com.petplatform.payment.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/** 支付拉起参数（ADR-016 §8；MOCK 通道返回 mock 标记+paymentNo，WECHAT 为 JSAPI 五参数）。 */
@Data
@Builder
public class PrepayView {

    private String paymentNo;
    private Map<String, String> params;
}
