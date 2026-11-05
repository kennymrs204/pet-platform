package com.petplatform.order.api;

import com.petplatform.order.api.dto.CreateOrderCommand;
import com.petplatform.order.api.dto.OrderView;

/**
 * 订单命令端口（表 Owner：order；ADR-016 §2/§4）。
 * markPaid/cancelTimeout 均为条件更新（先到先得，ADR-006-4），幂等语义见方法注释。
 */
public interface OrderCommandApi {

    /** 预约创建事务内建唯一订单（WAIT_PAY）；uk_appointment 兜底 1:1。 */
    OrderView createForAppointment(CreateOrderCommand command);

    /**
     * 支付成功推进 WAIT_PAY→PAID（条件更新）并发布 OrderPaidEvent。
     * 幂等：已 PAID 静默返回（事件重放）；已 CANCELLED（超时抢先，ADR-006-4 先到先得）记 WARN 返回，
     * 由人工退款通道处理，不抛错避免渠道无限重试。金额不符抛 60002。
     *
     * @param paymentMethod 落 ord_order.payment_method 的展示口径（ONLINE/OFFLINE），
     *                      由发布方（app 桥接）按渠道归一；线下收款（ADR-006 §6）须显式 OFFLINE
     */
    void markPaid(Long orderId, Long paidAmount, String paymentMethod);

    /**
     * 超时取消 WAIT_PAY→CANCELLED（条件更新）。
     * 返回 true=本次取消生效（调用方继续取消预约+释放资源），并发布 OrderTimeoutCancelledEvent
     * （reason=TIMEOUT，带权威 userId 供 AFTER_COMMIT 发顾客站内信）；false=已被支付/他人抢先（跳过本单，不发事件）。
     */
    boolean cancelTimeout(Long orderId);

    /**
     * 用户取消未支付预约的关单（ADR-023 §2）：同一 WAIT_PAY→CANCELLED 条件更新。
     * 取消真正生效后发布 OrderTimeoutCancelledEvent（reason=USER，与超时取消复用同一事件、以 reason 区分触发源），
     * 供 AFTER_COMMIT 给顾客发"预约已取消"站内信。false=状态漂移，调用方回滚，不发事件。
     */
    boolean cancelUnpaid(Long orderId);

    /**
     * 已支付预约取消（ADR-023 §2/§4）：PAID→REFUNDING 条件更新 + 插 ord_refund(APPLYING, apply_by=USER)。
     * 本期止于退款申请——渠道执行与审批（sys_config 507 阈值）随退款专段落地。
     * 0 行/超额分别抛 60003/60004，由调用方（取消事务）整体回滚。
     */
    void refundOnCancel(Long orderId, long refundAmountFen, String reason);
}
