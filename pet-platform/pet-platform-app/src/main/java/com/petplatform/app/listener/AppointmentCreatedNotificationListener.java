package com.petplatform.app.listener;

import com.petplatform.appointment.api.AppointmentQueryApi;
import com.petplatform.appointment.api.dto.AppointmentSelfView;
import com.petplatform.appointment.api.event.AppointmentCreatedEvent;
import com.petplatform.notification.api.NotificationApi;
import com.petplatform.notification.api.dto.NotificationPush;
import com.petplatform.store.api.StoreQueryApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 下单成功消息桥（ADR-045）：补上 {@link AppointmentCreatedEvent} 从建立起一直缺席的消费者——
 * 第五阶段 §5.2 与事件自身的 javadoc 都写着"AFTER_COMMIT → notification 发站内信"，
 * 本期把这一环接上，事件本身、发布点、预约/订单状态机一律不动。
 *
 * <p><b>为什么必须是 AFTER_COMMIT</b>：发布点在创建事务内部（{@code AppointmentCreateTxService} 第 158 行），
 * 同步 {@code @EventListener} 会在事务提交前就写通知 ⇒ 一旦出现"预约回滚了、下单成功消息却留下了"
 * 这种把消息变成事实源头的假象。AFTER_COMMIT 只在提交成功后触发，回滚则根本不会调用，
 * 这正是"消息是已发生事实的通知，不是事实的触发器"的机制表达。
 *
 * <p><b>AFTER_COMMIT 里抛异常会传给刚提交完的调用方</b>（数据已提交，用户却看到 500）——
 * 所以这里整段吞成 warn，比退款监听器更要小心。
 *
 * <p>幂等：走 {@link NotificationApi#pushOnce}，同一 {@code (收件人, APPOINTMENT_CREATED, appointment:id)}
 * 只落一条；重复事件（或将来接了重试/回放的投递）不会堆消息。
 */
@Slf4j
@Component
public class AppointmentCreatedNotificationListener {

    /** 与既有 REFUND_* 同一命名法（表注释与第一阶段矩阵都用 appointment 前缀，不另造 order 前缀）。 */
    static final String TYPE_APPOINTMENT_CREATED = "APPOINTMENT_CREATED";
    /** C 端点击跳转模块：值=路由 /orders/{bizId} 的 {bizId} 口径（ADR-041 既定契约）。 */
    static final String MODULE_APPOINTMENT = "appointment";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日");
    private static final DateTimeFormatter DEADLINE = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    private final AppointmentQueryApi appointmentQueryApi;
    private final StoreQueryApi storeQueryApi;
    private final NotificationApi notificationApi;

    public AppointmentCreatedNotificationListener(AppointmentQueryApi appointmentQueryApi,
                                                 StoreQueryApi storeQueryApi,
                                                 NotificationApi notificationApi) {
        this.appointmentQueryApi = appointmentQueryApi;
        this.storeQueryApi = storeQueryApi;
        this.notificationApi = notificationApi;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAppointmentCreated(AppointmentCreatedEvent event) {
        try {
            if (event == null || event.appointmentId() == null || event.userId() == null) {
                return;
            }
            AppointmentSelfView apt = appointmentQueryApi.selfDetail(event.userId(), event.appointmentId());
            if (apt == null) {
                // 读不到就不发：宁可少一条消息，也不发一条编不出内容的"下单成功"
                log.warn("下单成功通知跳过：读不到预约 appointmentId={}", event.appointmentId());
                return;
            }
            notificationApi.pushOnce(new NotificationPush(event.userId(), TYPE_APPOINTMENT_CREATED,
                    "预约下单成功", contentOf(apt), MODULE_APPOINTMENT, event.appointmentId()));
        } catch (RuntimeException e) {
            log.warn("下单成功通知旁路失败，不影响已提交的预约 appointmentId={}: {}",
                    event == null ? null : event.appointmentId(), e.getMessage());
        }
    }

    /**
     * 文案口径：<b>此刻预约是 PENDING_PAYMENT、订单是 WAIT_PAY</b>，所以只能说"提交成功、请完成支付"，
     * 绝不能出现"支付成功/预约已确认/已到账"——那是支付链落定之后的语义，由 ADR-046 的
     * {@link AppointmentPaidNotificationListener} 负责，两条消息各说各的时点。
     */
    private String contentOf(AppointmentSelfView apt) {
        StringBuilder sb = new StringBuilder("你的【").append(apt.getServiceNameSnapshot()).append("】预约已提交成功，");
        sb.append(apt.getBoardingCheckinDate() != null
                ? "入住 " + dayText(apt.getBoardingCheckinDate()) + " 至 " + dayText(apt.getBoardingCheckoutDate()) + "，"
                : "到店 " + whenText(apt) + "，");
        sb.append("请在 ").append(deadlineText(apt.getExpireAt())).append(" 前完成支付（¥")
                .append(yuan(apt.getPriceSnapshot())).append("）");
        String store = storeName(apt.getStoreId());
        sb.append(store == null ? "。" : "，门店：" + store + "。");
        return sb.toString();
    }

    /**
     * 门店名是<b>可选</b>正文摘要：查询抛运行时异常或名称空白时返回 null（B3 同款独立容错边界），
     * contentOf 回退到不带门店的基础正文——绝不能让摘要故障把整条下单成功通知拖进外层 catch。
     */
    private String storeName(Long storeId) {
        if (storeId == null) {
            return null;
        }
        try {
            var names = storeQueryApi.storeNames(java.util.List.of(storeId));
            String name = names == null ? null : names.get(storeId);
            return name == null || name.isBlank() ? null : name;
        } catch (RuntimeException e) {
            log.warn("下单成功通知读取门店名失败，降级为无门店名 storeId={}: {}", storeId, e.getMessage());
            return null;
        }
    }

    /** 包内共享：两条预约消息（下单/支付）必须把同一个到店时刻写成同一个样子，故不各写一份格式化。 */
    static String whenText(AppointmentSelfView apt) {
        if (apt.getServiceDate() == null) {
            return "时间待定";
        }
        return apt.getStartTime() == null ? dayText(apt.getServiceDate())
                : dayText(apt.getServiceDate()) + " " + apt.getStartTime();
    }

    static String dayText(LocalDate d) {
        return d == null ? "待定" : d.format(DAY);
    }

    /** 支付截止：取预约上的 expire_at（=创建 + sys_config 503 分钟），不自己加 15 分钟猜。 */
    private static String deadlineText(LocalDateTime expireAt) {
        return expireAt == null ? "规定时间" : expireAt.format(DEADLINE);
    }

    static String yuan(Long fen) {
        return BigDecimal.valueOf(fen == null ? 0L : fen).movePointLeft(2).toPlainString();
    }
}
