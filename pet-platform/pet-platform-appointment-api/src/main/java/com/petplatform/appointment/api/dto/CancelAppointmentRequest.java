package com.petplatform.appointment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户取消预约请求（ADR-023 §2，POST /customer/appointments/{id}/cancel）。
 * requestId 幂等（ADR-008-W：SETNX req:{userId}:{requestId} TTL 5min，重复 10005）。
 */
@Data
public class CancelAppointmentRequest {

    @NotBlank(message = "requestId必填")
    @Size(max = 64, message = "requestId过长")
    private String requestId;

    /** 取消原因（可空，落 cancelled_reason 与退款单 reason；空=「用户主动取消」） */
    @Size(max = 200, message = "取消原因最多200字")
    private String reason;
}
