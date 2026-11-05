package com.petplatform.order.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/** 订单视图（ADR-016；item_snapshot 直出，详情自足 ADR-002-8；Long 金额分经全局字符串化）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderView {

    private Long id;
    private String orderNo;
    private Long appointmentId;
    private Long userId;
    private Long storeId;
    /** 分，创建后不可变（ADR-006-9） */
    private Long amount;
    /** WAIT_PAY/PAID/WAIT_SERVICE/SERVING/COMPLETED/CANCELLED/REFUNDING/REFUNDED */
    private String status;
    private LocalDateTime payExpireAt;
    private LocalDateTime paidAt;
    /** ONLINE/OFFLINE */
    private String paymentMethod;
    private Map<String, Object> itemSnapshot;
    private LocalDateTime createdAt;
}
