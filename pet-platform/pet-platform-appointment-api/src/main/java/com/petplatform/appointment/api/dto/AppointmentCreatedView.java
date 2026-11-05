package com.petplatform.appointment.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/** 创建预约响应（第八阶段 §3.1：预约+订单+拉起参数一次返回）。 */
@Data
@Builder
public class AppointmentCreatedView {

    private Long appointmentId;
    private String appointmentNo;
    private String status;
    private LocalDateTime expireAt;
    private String serviceNameSnapshot;
    private Long priceSnapshot;
    private Integer durationSnapshot;
    private Long orderId;
    private String orderNo;
    private Long amount;
    private Map<String, String> paymentParams;
}
