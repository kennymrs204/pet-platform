package com.petplatform.app.listener;

import com.petplatform.notification.api.NotificationApi;
import com.petplatform.notification.api.dto.NotificationPush;
import com.petplatform.order.api.event.RefundAppliedEvent;
import com.petplatform.order.api.event.RefundDecidedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 退款消息桥（ADR-037 §5）：order 域只发事件，"发给谁、发什么"的判断落在装配根——
 * 因为收件人要查 employee 域，而 order→employee 不是矩阵边
 * （{@code AppointmentCancelListener}/{@code PaymentChainListener} 同构，同步 @EventListener 同事务）。
 *
 * <p><b>门店侧收件人由 {@link StoreMessageRecipients} 按权限码现算</b>（ADR-047）：
 * 原来这里写死 {@code roleCodes.contains("STORE_MANAGER")}，于是"只在本店有归属的店长"收到提醒，
 * 而 ADR-044 之后<b>同样持有 {@code order:refund:approve}、也确实能代到任何 OPEN 门店审批</b>的平台管理员
 * 一条都收不到（实测 ADMIN 的归属只有总部虚拟店 store_id=1，不在任何真实门店花名册里）——
 * "能批却不知道该批"比没权限更难解释。判据换成权限码后， ADMIN 由数据自动纳入，将来再授权也不用回来改常量。
 *
 * <p><b>biz_ref 放的是"点击要跳去哪"的主键，不是退款单 id</b>（ADR-041 订正）：
 * 客户三条消息统一 {@code module=appointment, bizId=appointmentId}，前端据此跳
 * {@code /orders/{appointmentId}}（现有 C 端订单详情路由，退款进度本来就长在那页上）；
 * 门店侧提醒用 {@code module=store_refund}，C 端识别不了这个模块 ⇒ 只读不可跳，
 * 也就不会把 refundId 当 appointmentId 拼进路由。<b>禁止前端猜关联</b>。
 * 两个退款事件都带 {@code appointmentId}（ADR-043 给 Decided 事件补齐，与 Applied 对称），
 * 本类不再回查退款单。
 *
 * <p><b>消息只落库、不投递</b>：仍然没有推送通道（App/短信/微信模板消息全未接），所以"送达"这件事
 * 靠两端各自拉取消息中心（C 端 ADR-041、B 端 ADR-047）；本类的职责边界没变——把时点与收件人固化成审计行。
 *
 * <p><b>时点由事件保证，不由文案保证</b>（ADR-043）：REFUND_SUCCESS 只可能来自
 * {@code outcome=SUCCESS}，而那个 outcome 是退款执行成功之后才发出来的事件字段；
 * 取消申请阶段只会发 REFUND_APPLIED。
 *
 * <p>失败一律吞成 warn：通知是旁路，绝不能把取消/审批主链回滚掉（同事务里抛异常就会回滚）。
 */
@Slf4j
@Component
public class RefundNotificationListener {

    private static final String MODULE_APPOINTMENT = "appointment";
    /** 门店侧待处理提醒：C 端不认这个模块，故只读不可跳；B 端认它——跳的是"退款处理中"这个<b>队列</b>
     *  而不是某一行（待处理本质是队列，bizId 是 refundId，而订单页的展开键是 orderId，两者不通）。 */
    private static final String MODULE_STORE_REFUND = "store_refund";
    /** 收件人判据：能批退款的人（ADR-047 由角色名改为权限码，ADMIN 由此自动纳入）。 */
    static final String CODE_APPROVE = "order:refund:approve";

    private final NotificationApi notificationApi;
    private final StoreMessageRecipients recipients;

    public RefundNotificationListener(NotificationApi notificationApi,
                                      StoreMessageRecipients recipients) {
        this.notificationApi = notificationApi;
        this.recipients = recipients;
    }

    /** 申请产生：告诉客户"取消申请已提交、等门店审核"，同时提醒能审批的人有一笔待处理。 */
    @EventListener
    public void onApplied(RefundAppliedEvent event) {
        try {
            String yuan = yuan(event.amountFen());
            notificationApi.push(new NotificationPush(event.userId(), "REFUND_APPLIED", "退款申请已提交",
                    "您的取消申请已提交，退款金额 ¥" + yuan + "，请等待门店审核。",
                    MODULE_APPOINTMENT, event.appointmentId()));
            for (Long userId : recipients.userIdsHolding(event.storeId(), CODE_APPROVE)) {
                notificationApi.push(new NotificationPush(userId, event.storeId(), "REFUND_APPLIED",
                        "有退款申请待处理",
                        "订单 " + event.orderId() + " 有一笔 ¥" + yuan + " 的退款申请待处理。",
                        MODULE_STORE_REFUND, event.refundId()));
            }
        } catch (RuntimeException e) {
            log.warn("退款申请通知旁路失败，不影响主链 refundId={}: {}", event.refundId(), e.getMessage());
        }
    }

    /**
     * 退款出结果：只通知客户（店长已在列表里看到状态变化）。
     *
     * <p><b>REFUND_SUCCESS 只代表"退款真的成功了"</b>——本事件由 approve 在
     * APPROVED→执行→SUCCESS→订单 REFUNDED 全链走完之后才发（ADR-043 §4），
     * 所以这里可以放心写"已退回原支付渠道"；Mock 阶段这句话描述的是模拟结果，接真实渠道后语义不变。
     * 被拒的一面刻意<b>不提任何理由</b>：ord_refund 没有拒绝理由列，编不出也不该编。
     */
    @EventListener
    public void onDecided(RefundDecidedEvent event) {
        try {
            boolean refunded = "SUCCESS".equals(event.outcome());
            String yuan = yuan(event.amountFen());
            notificationApi.push(new NotificationPush(event.userId(),
                    refunded ? "REFUND_SUCCESS" : "REFUND_REJECTED",
                    refunded ? "退款成功" : "退款申请未通过",
                    refunded
                            ? "您的退款 ¥" + yuan + " 已处理成功，已退回原支付渠道，可查看订单详情。"
                            : "您的取消申请 ¥" + yuan + " 未通过，订单仍然有效，可正常到店接受服务。",
                    MODULE_APPOINTMENT, event.appointmentId()));
        } catch (RuntimeException e) {
            log.warn("退款结果通知旁路失败，不影响主链 refundId={}: {}", event.refundId(), e.getMessage());
        }
    }

    private static String yuan(Long fen) {
        return BigDecimal.valueOf(fen == null ? 0L : fen).movePointLeft(2).toPlainString();
    }
}
