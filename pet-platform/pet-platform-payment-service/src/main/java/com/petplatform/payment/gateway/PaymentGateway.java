package com.petplatform.payment.gateway;

import java.util.Map;

/**
 * 支付渠道 SPI（ADR-016 §8；ADR-006-7 预留支付宝同接口扩展）。
 * 通道选择=配置 pet.payment.channel（mock|wechat）；无匹配实现则应用启动失败（fail-fast）。
 */
public interface PaymentGateway {

    /** 通道码（pay_payment.channel）：MOCK/WECHAT。 */
    String channel();

    /** 预下单：返回拉起参数（mock 通道仅回显 paymentNo）。 */
    Map<String, String> prepay(String paymentNo, long amountFen, String subject);

    /**
     * 验签并解析回调（验签即鉴权，第八阶段 §3.2）。
     * 返回 [paymentNo, channelTxnNo, paidAmountFen 字符串]；验签失败抛异常→应答 FAIL。
     */
    String[] verifyAndParse(String rawBody);

    /** 主动查单终态（超时竞态防线3）：SUCCESS/PROCESSING/FAILED/CLOSED。 */
    String queryStatus(String paymentNo);

    /** 渠道规范应答体。 */
    String ackSuccess();

    String ackFail(String message);
}
