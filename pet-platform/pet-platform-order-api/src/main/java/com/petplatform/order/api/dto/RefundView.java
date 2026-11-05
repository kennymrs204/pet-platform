package com.petplatform.order.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 退款单视图（ADR-037）。金额单位为**分**，前端统一走 fenToYuanText。
 *
 * <p>storeId/appointmentId/userId/paidAmount 由 {@code ord_order} 联出而非表内字段：
 * B 端要按门店筛、C 端要校"这单是我的"、通知要知道发给谁，缺一样就得再跳一次查询。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundView {

    private Long id;
    private String refundNo;
    private Long orderId;
    private Long appointmentId;
    private Long storeId;
    /** 下单客户（通知收件人）；不出前端，仅 app 装配层消费 */
    private Long userId;
    private Long amount;
    /** 订单实付（分），用于"已退 X／实付 Y"与超额自查 */
    private Long paidAmount;
    /** 客户申请理由 */
    private String reason;
    /** 门店拒绝理由（V17）；仅 REJECTED 有值 */
    private String rejectReason;
    /** APPLYING/APPROVED/REJECTED/EXECUTING/SUCCESS/FAILED */
    private String status;
    /** USER/STORE/ADMIN/SYSTEM */
    private String applyBy;
    /** 审批人角色快照 STORE_MANAGER/ADMIN（ADR-001-B 分级审计） */
    private String approveRole;
    private Long approverId;
    private LocalDateTime createdAt;
    private LocalDateTime refundedAt;
}
