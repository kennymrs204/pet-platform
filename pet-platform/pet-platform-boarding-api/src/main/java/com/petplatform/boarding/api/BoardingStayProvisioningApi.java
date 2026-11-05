package com.petplatform.boarding.api;

/**
 * 托管入住单创建端口（ADR-015 §2）。
 * 生产调用方 = 未来 AppointmentPaidEvent 订阅（MULTI_DAY 支付成功自动建 EXPECTED，§7.1 矩阵）；
 * 支付事件链未落地前本接口无调用方——保留扩展点，禁止伪造支付成功路径。
 */
public interface BoardingStayProvisioningApi {

    /**
     * 按预约幂等创建 EXPECTED 入住单（uk_appt 兜底；已存在活跃单静默返回）。
     * 预约不存在或非 MULTI_DAY → 业务异常（10004 / 10001）。
     */
    void ensureExpected(Long appointmentId);

    /**
     * 预约取消撤销入住单（ADR-023 §3，AppointmentCancelledEvent 由 app 监听器转入）：
     * 仅 EXPECTED 逻辑删（未入住才可撤；SERVING 后预约不可取消由 apt 状态闸保证）；
     * 无单/非 EXPECTED 静默返回，重放安全。
     */
    void cancelExpected(Long appointmentId);
}
