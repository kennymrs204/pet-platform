package com.petplatform.appointment.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 托管「已下单待支付」预约行（ADR-030，B 端托管页看板数据源）。
 * 只取 {@code apt_appointment} 自家列，不回查 catalog/order（口径同 ADR-013 门店预约列表）。
 *
 * <p>教训沿用 ADR-015：MyBatis 部分列投影 DTO 必须带 {@code @NoArgsConstructor @AllArgsConstructor}，
 * 只有 {@code @Builder} 会在结果映射期抛 10009。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BoardingLeadView {

    private Long id;
    private String appointmentNo;
    /** 下单人（C 端账号）。B 端据此经 user-api 取客户昵称，本视图不含手机号。 */
    private Long userId;
    private Long petId;
    private LocalDate boardingCheckinDate;
    private LocalDate boardingCheckoutDate;
    private String serviceName;
    /** 下单快照金额（分；Long 经全局 Jackson 字符串化）。托管按天计价后=日单价×晚数（ADR-025）。 */
    private Long priceSnapshot;
    /** 恒为 PENDING_PAYMENT，随行回显便于调用方审计不变量。 */
    private String status;
    /** 支付截止时间：到点由 OrderTimeoutJob 自动取消并释放笼位预占（ADR-016 超时链）。 */
    private LocalDateTime expireAt;
    private LocalDateTime createdAt;
}
