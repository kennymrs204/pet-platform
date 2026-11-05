package com.petplatform.app.listener;

import com.petplatform.appointment.api.AppointmentQueryApi;
import com.petplatform.appointment.api.dto.AppointmentSelfView;
import com.petplatform.notification.api.NotificationApi;
import com.petplatform.notification.api.dto.NotificationPush;
import com.petplatform.order.api.event.OrderPaidEvent;
import com.petplatform.pet.api.PetQueryApi;
import com.petplatform.pet.api.dto.PetSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 门店侧"新预约待服务"消息桥（ADR-047）：客户付了钱，<b>店里该有人知道来活了</b>。
 *
 * <p>与 {@link AppointmentPaidNotificationListener}（ADR-046，发给客户本人）订阅<b>同一个</b>
 * {@link OrderPaidEvent}，但写进<b>不同的收件箱</b>：客户那条 {@code store_id=0}、类型
 * {@code APPOINTMENT_PAID}；这条 {@code store_id=}该店、类型 {@code APPOINTMENT_TO_SERVE}。
 * 同一个事实、两种视角，因此不共用类型名——将来按 type 过滤消息时，"我看过的支付成功"和
 * "店里待处理的活"混在一个码上是会打架的。
 *
 * <p><b>为什么同样选 {@link OrderPaidEvent} 而不是 {@code AppointmentPaidEvent}</b>：后者没有 userId，
 * 而读预约详情（{@code selfDetail(userId, appointmentId)}）的入参就要 userId——全仓没有
 * {@code appointmentId→预约全貌} 的现成单读口。用不到收件人而只是拼不出内容，同样不该为通知去加读口
 * （ADR-041 §4"不为通知需求改主链形状"的同一条纪律）。OrderPaidEvent 三样齐全，
 * 且发布点严格等于"订单 {@code WAIT_PAY→PAID} 条件更新成功"。
 *
 * <p><b>AFTER_COMMIT + 重读 CONFIRMED</b>：OrderPaidEvent 发布那一刻预约还没 CONFIRMED（confirm 是同事务
 * 下一跳）。同步监听会写出"消息说已支付、库里还在待支付"的假话；AFTER_COMMIT 保证可观察时刻在整条链提交之后，
 * 链上任一环节回滚＝不执行＝没有消息。再自证一次状态，不信事件。
 *
 * <p>幂等走 {@link NotificationApi#pushOnce}：同一 {@code (收件人, 门店, APPOINTMENT_TO_SERVE, store_order:orderId)}
 * 只落一条 ⇒ 渠道重复回调、主动查单补偿、事件重放都堆不出第二条"来活了"。
 *
 * <p>AFTER_COMMIT 里抛异常会传播给刚提交成功的支付请求（钱已收、店员却看到 500），所以整段吞成 warn。
 */
@Slf4j
@Component
public class StoreAppointmentToServeNotificationListener {

    /** 门店视角的动作类型，与客户侧 APPOINTMENT_PAID 分列（见类注释）。 */
    static final String TYPE_APPOINTMENT_TO_SERVE = "APPOINTMENT_TO_SERVE";
    /** biz_ref 的 module：B 端据此跳 {@code /orders?openId={bizId}}；C 端不认这个模块 ⇒ 只读不可跳。 */
    static final String MODULE_STORE_ORDER = "store_order";
    /**
     * 收件人判据=能办到店签到的人（实测 V2 授予 STORE_MANAGER 与 RECEPTIONIST，V5 之后 ADMIN
     * 是否落入由 {@link StoreMessageRecipients} 按 {@code emp_role_permission} 现算，不写死）。
     */
    static final String CODE_RECIPIENT = "appointment:checkin";

    private static final String STATUS_CONFIRMED = "CONFIRMED";

    private final AppointmentQueryApi appointmentQueryApi;
    private final PetQueryApi petQueryApi;
    private final StoreMessageRecipients recipients;
    private final NotificationApi notificationApi;

    public StoreAppointmentToServeNotificationListener(AppointmentQueryApi appointmentQueryApi,
                                                      PetQueryApi petQueryApi,
                                                      StoreMessageRecipients recipients,
                                                      NotificationApi notificationApi) {
        this.appointmentQueryApi = appointmentQueryApi;
        this.petQueryApi = petQueryApi;
        this.recipients = recipients;
        this.notificationApi = notificationApi;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPaid(OrderPaidEvent event) {
        try {
            if (event == null || event.userId() == null || event.appointmentId() == null || event.orderId() == null) {
                return;
            }
            AppointmentSelfView apt = appointmentQueryApi.selfDetail(event.userId(), event.appointmentId());
            if (apt == null) {
                log.warn("门店新预约消息跳过：读不到预约 appointmentId={}", event.appointmentId());
                return;
            }
            if (!STATUS_CONFIRMED.equals(apt.getStatus())) {
                log.warn("门店新预约消息跳过：预约不在 CONFIRMED appointmentId={} status={}",
                        event.appointmentId(), apt.getStatus());
                return;
            }
            if (apt.getStoreId() == null) {
                log.warn("门店新预约消息跳过：预约无门店 appointmentId={}", event.appointmentId());
                return;
            }
            List<Long> userIds = recipients.userIdsHolding(apt.getStoreId(), CODE_RECIPIENT);
            if (userIds.isEmpty()) {
                // 店里没人有这个码 ⇒ 不发。这不是失败：通知是旁路，编不出收件人时宁可少一条，
                // 也不能把它塞给"碰巧在店里的任何人"（那会把提醒变成噪音，还会泄露客户信息给无关岗位）。
                log.debug("门店新预约消息跳过：该店无人持有 {} storeId={}", CODE_RECIPIENT, apt.getStoreId());
                return;
            }
            String content = contentOf(apt);
            for (Long userId : userIds) {
                notificationApi.pushOnce(new NotificationPush(userId, apt.getStoreId(), TYPE_APPOINTMENT_TO_SERVE,
                        "新预约待服务", content, MODULE_STORE_ORDER, event.orderId()));
            }
        } catch (RuntimeException e) {
            log.warn("门店新预约消息旁路失败，不影响已提交的支付 appointmentId={}: {}",
                    event == null ? null : event.appointmentId(), e.getMessage());
        }
    }

    /**
     * 文案只说库里已经成立的事：<b>已支付确认 + 到店时间 + 服务名 + 宠物名</b>。
     * 刻意不写"请指派员工"或"已分配给谁"——指派是另一个动作（洗护写侧尚未接），编出来就是假指令。
     * 宠物名读不到时整段降级成"有一笔预约"，不写【null】也不编一个名字。
     */
    private String contentOf(AppointmentSelfView apt) {
        String pet = petName(apt.getPetId());
        StringBuilder sb = new StringBuilder(pet == null ? "有一笔" : "【" + pet + "】的");
        sb.append("【").append(apt.getServiceNameSnapshot()).append("】预约已支付确认，");
        sb.append(apt.getBoardingCheckinDate() != null
                ? "入住 " + AppointmentCreatedNotificationListener.dayText(apt.getBoardingCheckinDate())
                        + " 至 " + AppointmentCreatedNotificationListener.dayText(apt.getBoardingCheckoutDate())
                : "到店 " + AppointmentCreatedNotificationListener.whenText(apt));
        sb.append("，请到订单页安排服务。");
        return sb.toString();
    }

    /**
     * 宠物名是<b>可选</b>正文摘要：查询抛运行时异常时降级为无名（B3 同款独立容错边界），
     * 由 {@link #contentOf} 回退"有一笔…"基础文案——绝不能让摘要故障把整条门店新预约通知
     * 连同全部收件人一起拖进外层 catch（那正是本方法此前缺 try/catch 的事故形态）。
     */
    private String petName(Long petId) {
        if (petId == null) {
            return null;
        }
        try {
            List<PetSummary> pets = petQueryApi.summaries(List.of(petId));
            return pets == null ? null : pets.stream()
                    .filter(p -> p != null && petId.equals(p.getId()) && p.getName() != null && !p.getName().isBlank())
                    .map(PetSummary::getName)
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("门店新预约消息读取宠物名失败，降级为无宠物名 petId={}: {}", petId, e.getMessage());
            return null;
        }
    }
}
