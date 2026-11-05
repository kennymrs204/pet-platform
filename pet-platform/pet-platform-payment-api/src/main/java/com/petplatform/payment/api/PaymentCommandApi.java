package com.petplatform.payment.api;

import com.petplatform.payment.api.dto.OfflinePaymentView;
import com.petplatform.payment.api.dto.PrepayView;

/**
 * 支付命令端口（表 Owner：payment，pay_payment，ADR-016 §8）。
 * 幂等四层防线（ADR-006-3）：uk_channel_txn → 订单条件更新（order 侧）→ queryFinalStatus
 * （超时任务主动查单）→ 渠道重试应答。
 */
public interface PaymentCommandApi {

    /**
     * 生成/复用 PROCESSING 流水并返回拉起参数（同单重复调用复用未终态流水）。
     * 仅 WAIT_PAY 语义由调用方（order 端点）校验。
     */
    PrepayView createPrepay(Long orderId, Long amount, String subject);

    /**
     * 渠道回调处理：验签→幂等落流水 SUCCESS→事务内发布 PaymentSucceededEvent（同步链推进订单）。
     * 返回渠道规范应答体（不套 Result）。
     */
    String handleCallback(String channel, String rawBody);

    /**
     * 查询订单支付终态（超时任务竞态前置，ADR-016 §4）：
     * SUCCESS / PROCESSING（渠道仍无果）/ NONE（无流水）；查到渠道已付会就地补偿推进（幂等）。
     * 渠道查询失败抛异常——调用方按"宁可晚取消"跳过本单。
     */
    String queryFinalStatus(Long orderId);

    /**
     * 线下收款登记（ADR-006 §6）：写 channel=OFFLINE 的 PROCESSING 流水，<b>不推进任何状态</b>——
     * 登记≠到账，推进只能走 {@link #confirmOffline}。仅 WAIT_PAY 订单语义由调用方校验。
     * 同单已有待复核登记时幂等复用（与 createPrepay 同法）。
     *
     * @throws com.petplatform.common.error.BusinessException 10001 参数非法/备注超宽；
     *                                                     10006 该单线下收款已复核（订单应已 PAID）
     */
    OfflinePaymentView registerOffline(Long orderId, Long amount, Long registrantUserId, String note);

    /**
     * 线下收款复核（ADR-006 §6 双人原则）：CAS {@code PROCESSING→SUCCESS} 后发布
     * PaymentSucceededEvent(channel=OFFLINE)，既有桥接链同事务推进 订单 PAID→预约 CONFIRMED
     * →入住单 EXPECTED。复核人≠登记人是硬闸（比较登记时落库的 registered_by）。
     *
     * @throws com.petplatform.common.error.BusinessException 10001 参数非法；10003 复核人=登记人；
     *                                                     10006 未登记/已复核/并发复核落败
     */
    OfflinePaymentView confirmOffline(Long orderId, Long confirmerUserId);
}
