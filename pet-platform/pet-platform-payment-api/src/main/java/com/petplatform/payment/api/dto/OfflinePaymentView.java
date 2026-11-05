package com.petplatform.payment.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 线下收款流水视图（ADR-006 §6）。金额=分；登记/复核人存 userId，
 * 姓名回显由调用方（app 装配层）拼装——payment 模块不依赖 user（依赖矩阵）。
 */
@Data
@Builder
public class OfflinePaymentView {
    private Long id;
    private String paymentNo;
    private Long orderId;
    private Long amount;
    /** PROCESSING=已登记待复核 / SUCCESS=已复核（推进 PAID 由事件链完成） */
    private String status;
    private Long registeredBy;
    private String registeredNote;
    private Long confirmedBy;
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
}
