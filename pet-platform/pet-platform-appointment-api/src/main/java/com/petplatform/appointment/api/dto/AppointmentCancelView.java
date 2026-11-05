package com.petplatform.appointment.api.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 取消结果视图（ADR-023 §2）。refundState：
 * NONE=未支付无退款；APPLYING=已付取消，退款单已建（渠道执行随退款专段，本期止于申请/待审）。
 */
@Data
@Builder
public class AppointmentCancelView {

    private Long appointmentId;
    private String appointmentNo;
    /**
     * 取消后的预约状态（ADR-043 §2）：
     * 未支付=当场 {@code CANCELLED}；已支付=只提交申请，返回 {@code CANCEL_REQUESTED}
     * （真正落 CANCELLED 要等门店审批 + 退款成功）。
     */
    private String status;
    private String refundState;
    /** 退款申请金额（分）；NONE 时 0 */
    private Long refundAmount;
}
