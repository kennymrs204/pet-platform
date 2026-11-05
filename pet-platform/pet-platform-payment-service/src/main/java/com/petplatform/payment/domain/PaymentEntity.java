package com.petplatform.payment.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.petplatform.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 支付流水（pay_payment，表 Owner=payment，ADR-016 §8；SUCCESS 每单至多一条，Service 层复核）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pay_payment")
public class PaymentEntity extends BaseEntity {

    private String paymentNo;
    private Long orderId;
    /** WECHAT/ALIPAY(预留)/OFFLINE/MOCK(dev-only) */
    private String channel;
    private String channelTxnNo;
    private String channelPrepayId;
    private Long amount;
    /** PROCESSING/SUCCESS/FAILED/CLOSED */
    private String status;
    private Boolean amountMismatch;
    private String callbackRaw;
    /** 线下收款（ADR-006 §6）：登记人 userId（复核≠登记的判据）/备注/复核人 userId；线上渠道恒 NULL */
    private Long registeredBy;
    private String registeredNote;
    private Long confirmedBy;
    private LocalDateTime paidAt;
    private Integer version;
}
