package com.petplatform.order.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.petplatform.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 退款业务单（ord_refund，表 Owner=order，ADR-023 §4 / 第六阶段 §7）。
 * 本期生命周期止于 APPLYING（申请/待审批）；审批与渠道执行随退款专段落地。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ord_refund")
public class RefundEntity extends BaseEntity {

    private String refundNo;
    private Long orderId;
    /** 分，≤订单实付（第六阶段 §7 :125） */
    private Long amount;
    private String reason;
    /** 门店拒绝理由（V17 新增）；仅 REJECTED 行有值，存量行为 NULL */
    private String rejectReason;
    /** APPLYING/APPROVED/REJECTED/EXECUTING/SUCCESS/FAILED */
    private String status;
    /** USER/STORE/ADMIN/SYSTEM */
    private String applyBy;
    private String approveRole;
    private Long approverId;
    private Boolean manualOverride;
    private LocalDateTime refundedAt;
    private Integer version;
}
