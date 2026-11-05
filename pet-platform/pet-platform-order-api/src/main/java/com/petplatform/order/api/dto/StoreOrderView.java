package com.petplatform.order.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * B 端门店订单视图（ADR-019 §5）。只直出 ord_order 列 + item_snapshot 已解析字段；
 * 客户身份不在此处——依赖矩阵里 order 不依赖 user，由 app 装配层拼装。
 * 金额单位=分，Long 经全局 Jackson 字符串化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreOrderView {

    private Long id;
    private String orderNo;
    private Long appointmentId;
    private Long userId;
    /** WAIT_PAY/PAID/WAIT_SERVICE/SERVING/COMPLETED/CANCELLED/REFUNDING/REFUNDED */
    private String status;
    private Long amount;
    /** 实收（分）；未支付与已取消为 0 */
    private Long paidAmount;
    /** ONLINE/OFFLINE */
    private String paymentMethod;
    private LocalDateTime payExpireAt;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
    /** item_snapshot 解析结果；解析失败降级 null，不抛 10009 */
    private String serviceName;
    private Integer durationMin;
}
