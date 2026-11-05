package com.petplatform.appointment.api;

import com.petplatform.appointment.api.dto.AppointmentCreateRequest;
import com.petplatform.appointment.api.dto.AppointmentCreatedView;

/**
 * 预约状态命令（表 Owner：appointment，ADR-016 §2/§4）。
 * 全部条件更新（WHERE status=期望前态）：幂等重放静默跳过，非法迁移抛 50003；
 * 仅供 app 装配层事件桥/超时任务在各自事务内调用，业务模块不得反向驱动。
 */
public interface AppointmentCommandApi {

    /**
     * B 端代客建单（客户 360 → 新增预约）：与 C 端 {@code AppointmentCreateService.create} 同一条
     * 校验/落库链，两点差异——下单人 = {@code customerUserId}（顾客，非当前登录员工）、
     * source 落 {@code RECEPTIONIST} 可追溯；服务必须属于 {@code storeId}（防跨店代建，10004）。
     *
     * <p>顾客"在本店客户集"的校验由调用方（app 装配层 {@code requireCustomer}）先行——
     * 本层不感知客户集口径。建单不代付：落 PENDING_PAYMENT 由顾客在 C 端支付。
     */
    AppointmentCreatedView createForCustomer(Long storeId, Long customerUserId, AppointmentCreateRequest req);

    /**
     * 支付成功推进 PENDING_PAYMENT→CONFIRMED 并发布 AppointmentPaidEvent。
     * 已 CONFIRMED 及之后（重放）静默返回；PENDING_PAYMENT 之外首次遇到按 50003。
     */
    void confirm(Long appointmentId);

    /**
     * 超时取消 PENDING_PAYMENT→CANCELLED（条件更新）。
     * 0 行抛 50003——调用方（超时编排）据此回滚整单取消，防"单已取消约未取消"半截态。
     */
    void cancelTimeout(Long appointmentId);

    /**
     * 退款成功后落定取消（ADR-043 §4）：CANCEL_REQUESTED→CANCELLED + 释放占用 +
     * 发 {@code AppointmentCancelledEvent}（MULTI_DAY 时撤销 EXPECTED 住单）。
     *
     * <p><b>取消的"生效"只发生在这里，不在用户提交申请时</b>。调用方=app 装配根对
     * {@code RefundDecidedEvent(SUCCESS)} 的同步监听（与退款写同一本地事务）；
     * 0 行抛 50003 整笔回滚——不允许出现"钱已退而预约还在审核中"。
     */
    void landCancellationAfterRefund(Long appointmentId);

    /**
     * 退款被拒恢复原态（ADR-043 §3）：CANCEL_REQUESTED→CONFIRMED，并清掉申请留下的
     * {@code cancel_by_role/cancelled_reason}（预约重新可服务）。占用从未释放，故无需重新占位。
     * 0 行同样抛 50003 整笔回滚（拒绝不能只改退款单）。
     */
    void restoreAfterRefundReject(Long appointmentId);
}
