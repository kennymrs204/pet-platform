package com.petplatform.app.listener;

import com.petplatform.appointment.api.AppointmentQueryApi;
import com.petplatform.appointment.api.dto.AppointmentSelfView;
import com.petplatform.notification.api.NotificationApi;
import com.petplatform.notification.api.dto.NotificationPush;
import com.petplatform.order.api.event.OrderTimeoutCancelledEvent;
import com.petplatform.store.api.StoreQueryApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;

/**
 * 未支付取消消息桥：补上「顾客主动取消未支付单」与「支付超时自动取消」两条链一直缺席的顾客站内信。
 * 订阅 {@link OrderTimeoutCancelledEvent}（{@code reason} 区分触发源），事件本身/发布点/状态机一律不动，
 * 这里只是<b>多一个消费者</b>（与 ADR-045/046 的下单/支付桥同款做法）。
 *
 * <p><b>为什么 AFTER_COMMIT</b>：发布点在取消事务内（订单 WAIT_PAY→CANCELLED 的 CAS + 预约取消 + 释放资源
 * 同生同死）。同步监听会在提交前写消息，一旦取消链回滚就留下"已取消"的假事实。走 AFTER_COMMIT ⇒ 只有整条
 * 取消链提交后才发；任一环回滚则根本不调用。再加重读自证：写前重读预约，不是 CANCELLED 就跳过并告警
 * （不信事件、不信前端，与 {@link AppointmentPaidNotificationListener} 同口径）。
 *
 * <p><b>幂等</b>：走 {@link NotificationApi#pushOnce}，同一 {@code (收件人, APPOINTMENT_CANCELLED, appointment:id)}
 * 只落一条。一个预约只能被取消一次（WAIT_PAY→CANCELLED 是终态），主动与超时两条路径共享同一 appointmentId，
 * 故同一预约至多一条取消消息；事件重放/重复回调也堆不出第二条。
 *
 * <p><b>文案</b>：只说"未支付、已取消、无需退款"，绝不暗示退款已发生——已支付取消走退款链
 * （{@code RefundAppliedEvent}），不经本事件，所以本桥永远不会给已付单发"取消"消息。
 *
 * <p>AFTER_COMMIT 里抛异常会传播给刚提交成功的取消请求（约已取消、用户却看到 500）⇒ 整段吞成 warn。
 */
@Slf4j
@Component
public class AppointmentCancelledNotificationListener {

    /** 与 APPOINTMENT_CREATED/PAID 同族命名（appointment 前缀）；刻意不复用 REFUND_*——取消≠退款。 */
    static final String TYPE_APPOINTMENT_CANCELLED = "APPOINTMENT_CANCELLED";
    /** 只有这个态才配得上"预约已取消"。 */
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final AppointmentQueryApi appointmentQueryApi;
    private final StoreQueryApi storeQueryApi;
    private final NotificationApi notificationApi;

    public AppointmentCancelledNotificationListener(AppointmentQueryApi appointmentQueryApi,
                                                    StoreQueryApi storeQueryApi,
                                                    NotificationApi notificationApi) {
        this.appointmentQueryApi = appointmentQueryApi;
        this.storeQueryApi = storeQueryApi;
        this.notificationApi = notificationApi;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUnpaidCancelled(OrderTimeoutCancelledEvent event) {
        try {
            if (event == null || event.userId() == null || event.appointmentId() == null) {
                return;
            }
            AppointmentSelfView apt = appointmentQueryApi.selfDetail(event.userId(), event.appointmentId());
            if (apt == null) {
                log.warn("取消通知跳过：读不到预约 appointmentId={}", event.appointmentId());
                return;
            }
            if (!STATUS_CANCELLED.equals(apt.getStatus())) {
                log.warn("取消通知跳过：预约不在 CANCELLED appointmentId={} status={}",
                        event.appointmentId(), apt.getStatus());
                return;
            }
            boolean byTimeout = OrderTimeoutCancelledEvent.REASON_TIMEOUT.equals(event.reason());
            notificationApi.pushOnce(new NotificationPush(event.userId(), TYPE_APPOINTMENT_CANCELLED,
                    "预约已取消", contentOf(apt, byTimeout),
                    AppointmentCreatedNotificationListener.MODULE_APPOINTMENT, event.appointmentId()));
        } catch (RuntimeException e) {
            log.warn("取消通知旁路失败，不影响已提交的取消 appointmentId={}: {}",
                    event == null ? null : event.appointmentId(), e.getMessage());
        }
    }

    /**
     * 文案口径：写这条时订单已 CANCELLED、预约已 CANCELLED 且从未支付，所以只能说"已取消、无需退款"，
     * 按 reason 区分「顾客主动」与「超时自动」；绝不能出现"退款成功/已退款"（那是退款链的语义）。
     */
    private String contentOf(AppointmentSelfView apt, boolean byTimeout) {
        StringBuilder sb = new StringBuilder(byTimeout
                ? "你的【" + apt.getServiceNameSnapshot() + "】预约因超时未支付，已自动取消"
                : "你已取消【" + apt.getServiceNameSnapshot() + "】预约");
        sb.append(apt.getBoardingCheckinDate() != null
                ? "（入住 " + AppointmentCreatedNotificationListener.dayText(apt.getBoardingCheckinDate()) + "）"
                : "（" + AppointmentCreatedNotificationListener.whenText(apt) + "）");
        sb.append("。该预约尚未支付，无需退款。");
        String store = storeName(apt.getStoreId());
        if (store != null) {
            sb.append("门店：").append(store).append("。");
        }
        return sb.toString();
    }

    private String storeName(Long storeId) {
        if (storeId == null) {
            return null;
        }
        try {
            Map<Long, String> names = storeQueryApi.storeNames(List.of(storeId));
            return names == null ? null : names.get(storeId);
        } catch (RuntimeException e) {
            // 门店名只是可选正文摘要：查询失败降级为"不带门店名"，绝不能让摘要故障吞掉核心取消通知
            log.warn("取消通知读取门店名失败，降级为无门店名 storeId={}: {}", storeId, e.getMessage());
            return null;
        }
    }
}
