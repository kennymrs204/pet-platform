package com.petplatform.order.api.event;

import java.time.LocalDateTime;

/**
 * 未支付订单取消事件（ADR-016 §4 起；notification 段接入后由 AFTER_COMMIT 订阅，给顾客发站内信）。
 *
 * <p>覆盖两条未支付取消路径，用 {@link #reason} 区分触发源（文案不同，业务结果相同）：
 * <ol>
 *   <li>顾客主动取消未支付预约（{@code OrderCommandService.cancelUnpaid}，reason={@link #REASON_USER}）；</li>
 *   <li>支付超时系统自动取消（{@code OrderCommandService.cancelTimeout}，reason={@link #REASON_TIMEOUT}）。</li>
 * </ol>
 * 两者都只在 {@code WAIT_PAY→CANCELLED} 条件更新**真正生效**后发布；CAS 落败或事务回滚都不发，
 * 故监听器写出的"已取消"必是已提交的事实。
 *
 * <p>{@link #userId} 是通知收件人，取自权威订单行（发布点 {@code order.getUserId()}），
 * 不让 AFTER_COMMIT 监听器再回读订单。类名沿用历史（最初只有超时一路）；按"不为命名重构事件体系"的约束
 * 保留原名，触发源改由 {@link #reason} 表达。已支付取消走退款链（{@code RefundAppliedEvent}），不经本事件，
 * 因此本事件绝不会给已付单发"取消"消息。
 */
public record OrderTimeoutCancelledEvent(
        Long orderId,
        Long appointmentId,
        Long userId,
        String reason,
        LocalDateTime occurredAt
) {
    /** 顾客主动取消未支付订单。 */
    public static final String REASON_USER = "USER";
    /** 支付超时系统自动取消。 */
    public static final String REASON_TIMEOUT = "TIMEOUT";
}
