package com.petplatform.app.listener;

import com.petplatform.appointment.api.AppointmentQueryApi;
import com.petplatform.appointment.api.dto.AppointmentSelfView;
import com.petplatform.notification.api.NotificationApi;
import com.petplatform.notification.api.dto.NotificationPush;
import com.petplatform.order.api.event.OrderPaidEvent;
import com.petplatform.store.api.StoreQueryApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 支付成功消息桥（ADR-046）：补上 V1 表注释里预留却从未有人写入的 {@code APPOINTMENT_PAID}。
 * 支付链本身（事件形状、发布点、状态机、托管预生成）一字未动，这里只是<b>多一个消费者</b>。
 *
 * <p><b>为什么订阅 {@link OrderPaidEvent} 而不是 AppointmentPaidEvent</b>（审计结论，非偏好）：
 * {@code AppointmentPaidEvent(appointmentId, orderId, storeId, petId, multiDay, occurredAt)}
 * <b>没有收件人</b>，而全仓没有 {@code appointmentId→userId} 的现成读口（{@code selfDetail} 的入参就要 userId），
 * 选它就得改事件字段或新增跨模块查询——那是动支付链，本 ADR 明令禁止。
 * {@code OrderPaidEvent(orderId, appointmentId, userId)} 三样齐全，且发布点严格等于
 * "订单 {@code WAIT_PAY→PAID} 条件更新成功"（重放与并发败方都 return，不发事件）。
 *
 * <p><b>AFTER_COMMIT 把"事件瞬间"补成了"事实落定"</b>：OrderPaidEvent 发布那一刻预约还没 CONFIRMED
 * （confirm 是同事务的下一跳）。同步监听就会写出"消息说已确认、库里还是 PENDING_PAYMENT"的假话。
 * 走 AFTER_COMMIT 后，可观察时刻必然在整条同步链（流水 SUCCESS→订单 PAID→预约 CONFIRMED→入住单 EXPECTED）
 * 提交之后；链上任一环节异常＝整体回滚＝AFTER_COMMIT 不执行＝没有消息。
 * 再加一道 {@link #isConfirmed} 自证：写消息前重读预约，不是 CONFIRMED 就跳过并告警。
 *
 * <p>幂等：走 {@link NotificationApi#pushOnce}，同一 {@code (收件人, APPOINTMENT_PAID, appointment:id)}
 * 只落一条 ⇒ 渠道重复回调、主动查单补偿、事件重放都堆不出第二条"支付成功"。
 *
 * <p>AFTER_COMMIT 里抛异常会传播给刚提交成功的支付请求（钱已扣、用户却看到 500），所以整段吞成 warn。
 */
@Slf4j
@Component
public class AppointmentPaidNotificationListener {

    /** 沿用 V1 列注释预留的类型名（appointment 前缀，与 ADR-045 的 APPOINTMENT_CREATED 同族），不另造 ORDER_PAID。 */
    static final String TYPE_APPOINTMENT_PAID = "APPOINTMENT_PAID";
    /** 只有这个态才配得上"支付成功，预约已确认"。 */
    private static final String STATUS_CONFIRMED = "CONFIRMED";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日");

    private final AppointmentQueryApi appointmentQueryApi;
    private final StoreQueryApi storeQueryApi;
    private final NotificationApi notificationApi;

    public AppointmentPaidNotificationListener(AppointmentQueryApi appointmentQueryApi,
                                               StoreQueryApi storeQueryApi,
                                               NotificationApi notificationApi) {
        this.appointmentQueryApi = appointmentQueryApi;
        this.storeQueryApi = storeQueryApi;
        this.notificationApi = notificationApi;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPaid(OrderPaidEvent event) {
        try {
            if (event == null || event.userId() == null || event.appointmentId() == null) {
                return;
            }
            AppointmentSelfView apt = appointmentQueryApi.selfDetail(event.userId(), event.appointmentId());
            if (apt == null) {
                log.warn("支付成功通知跳过：读不到预约 appointmentId={}", event.appointmentId());
                return;
            }
            if (!isConfirmed(apt)) {
                log.warn("支付成功通知跳过：预约不在 CONFIRMED appointmentId={} status={}",
                        event.appointmentId(), apt.getStatus());
                return;
            }
            notificationApi.pushOnce(new NotificationPush(event.userId(), TYPE_APPOINTMENT_PAID,
                    "支付成功", contentOf(apt), AppointmentCreatedNotificationListener.MODULE_APPOINTMENT,
                    event.appointmentId()));
        } catch (RuntimeException e) {
            log.warn("支付成功通知旁路失败，不影响已提交的支付 appointmentId={}: {}",
                    event == null ? null : event.appointmentId(), e.getMessage());
        }
    }

    /** 重读到的预约状态是唯一被采信的"已确认"证据——不信事件，也不信前端。 */
    private boolean isConfirmed(AppointmentSelfView apt) {
        return STATUS_CONFIRMED.equals(apt.getStatus());
    }

    /**
     * 文案口径：写这条时订单已是 PAID、预约已是 CONFIRMED，所以只能说"已支付成功、预约已确认"，
     * 反过来绝不能出现"待支付/请完成支付/支付处理中"（那是 ADR-045 那条消息的语义），
     * 也不能蹭"退款"或"已取消"字眼——金额取预约价快照：它与订单金额同源创建、
     * 且支付成功的前提就是渠道实付额与该值相等（{@code AMOUNT_MISMATCH} 否则不推进）。
     */
    private String contentOf(AppointmentSelfView apt) {
        StringBuilder sb = new StringBuilder("你的【").append(apt.getServiceNameSnapshot())
                .append("】预约已支付成功，预约已确认。");
        sb.append(apt.getBoardingCheckinDate() != null
                ? "入住 " + apt.getBoardingCheckinDate().format(DAY) + " 至 "
                        + (apt.getBoardingCheckoutDate() == null ? "待定" : apt.getBoardingCheckoutDate().format(DAY))
                : "到店 " + AppointmentCreatedNotificationListener.whenText(apt));
        sb.append("，支付金额 ¥").append(AppointmentCreatedNotificationListener.yuan(apt.getPriceSnapshot()));
        String store = storeName(apt.getStoreId());
        sb.append(store == null ? "。" : "，门店：" + store + "。");
        return sb.toString();
    }

    /**
     * 门店名是<b>可选</b>正文摘要：查询抛运行时异常或名称空白时返回 null（B3 同款独立容错边界），
     * contentOf 回退到不带门店的基础正文——绝不能让摘要故障把整条支付成功通知拖进外层 catch。
     */
    private String storeName(Long storeId) {
        if (storeId == null) {
            return null;
        }
        try {
            Map<Long, String> names = storeQueryApi.storeNames(List.of(storeId));
            String name = names == null ? null : names.get(storeId);
            return name == null || name.isBlank() ? null : name;
        } catch (RuntimeException e) {
            log.warn("支付成功通知读取门店名失败，降级为无门店名 storeId={}: {}", storeId, e.getMessage());
            return null;
        }
    }
}
