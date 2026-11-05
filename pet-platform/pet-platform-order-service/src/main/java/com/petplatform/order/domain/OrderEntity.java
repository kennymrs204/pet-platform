package com.petplatform.order.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.petplatform.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 交易单（ord_order，聚合根，表 Owner=order，ADR-016）。
 * version 条件更新（markPaid/cancelTimeout 在 OrderMapper 手写 SQL），金额全程分。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ord_order")
public class OrderEntity extends BaseEntity {

    private String orderNo;
    private Long appointmentId;
    private Long userId;
    private Long storeId;
    private Long amount;
    private Long discountAmount;
    private Long paidAmount;
    /** WAIT_PAY/PAID/WAIT_SERVICE/SERVING/COMPLETED/CANCELLED/REFUNDING/REFUNDED */
    private String status;
    private LocalDateTime payExpireAt;
    private LocalDateTime paidAt;
    /** ONLINE/OFFLINE */
    private String paymentMethod;
    private Long offlineConfirmedBy;
    private Boolean paidThenCancelled;
    /** item_snapshot JSON 列的字符串形态（服务名/时长/单价快照） */
    private String itemSnapshot;
    private Integer version;
}
