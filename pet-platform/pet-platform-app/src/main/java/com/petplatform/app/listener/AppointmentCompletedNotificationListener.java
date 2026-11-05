package com.petplatform.app.listener;

import com.petplatform.appointment.api.event.AppointmentCompletedEvent;
import com.petplatform.notification.api.NotificationApi;
import com.petplatform.notification.api.dto.NotificationPush;
import com.petplatform.pet.api.PetQueryApi;
import com.petplatform.pet.api.dto.PetSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * "服务已完成"顾客消息桥（ADR-053）。此前的顾客通知止步于"支付成功"（ADR-046）——
 * 服务做完的那一刻顾客毫无感知，"接走宠物"全靠顾客自己想起来。本监听器补上最后一环：
 * 预约落到 COMPLETED（两个唯一入口都发 {@link AppointmentCompletedEvent}——
 * INSTANT 履约完成 / 托管离店联动）→ 给顾客推一条完成消息，文案按来源分支：
 * <ul>
 *   <li>INSTANT_FINISH：到店类服务做完 → "宠物可以接走了"（用户点名的核心场景）；</li>
 *   <li>BOARDING_CHECKOUT：托管办理离店 → "已办理离店"（接走动作与离店同时发生，消息是回执）。</li>
 * </ul>
 *
 * <p>B3：事件带 {@code petId/actualStartAt/actualEndAt} 时，正文补宠物名和真实履约跨度。
 * INSTANT 只写"从开始服务到完成，用时 X"——开始动作允许提前触发，不能暗示成净手工服务时长；
 * BOARDING 只写"本次入住 X"——用实际入住到离店的时间，不拿计划日期凑。宠物名或时长任一不可用
 * 就降级到原基础文案；宠物查询在独立 try/catch 里失败也只丢摘要，绝不拖累完成通知本身。
 *
 * <p>收件人=预约的顾客本人（事件带 userId）；个人消息走 {@code storeId=0} 简写形态（C 端收件箱语义）；
 * {@code bizModule=appointment, bizId=appointmentId} → C 端点击跳 {@code /orders/{appointmentId}}
 * （ADR-041/037 既定口径，不重蹈"退款 id 当预约 id"的覆辙）。幂等：pushOnce 同
 * (顾客, APPOINTMENT_COMPLETED, appointment:id) 只落一条——重复完成事件（幂等重放不会发布，
 * 此处再兜一层）不会堆消息。AFTER_COMMIT + 整段吞异常（同既有监听器纪律）。
 */
@Slf4j
@Component
public class AppointmentCompletedNotificationListener {

    static final String TYPE_APPOINTMENT_COMPLETED = "APPOINTMENT_COMPLETED";
    static final String MODULE_APPOINTMENT = "appointment";

    private final NotificationApi notificationApi;
    private final PetQueryApi petQueryApi;

    public AppointmentCompletedNotificationListener(NotificationApi notificationApi, PetQueryApi petQueryApi) {
        this.notificationApi = notificationApi;
        this.petQueryApi = petQueryApi;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAppointmentCompleted(AppointmentCompletedEvent event) {
        try {
            if (event == null || event.userId() == null || event.appointmentId() == null) {
                return;
            }
            boolean boarding = AppointmentCompletedEvent.SOURCE_BOARDING_CHECKOUT.equals(event.source());
            String title = boarding ? "托管已结束" : "服务已完成";
            String content = contentOf(boarding, event.serviceName(),
                    petName(event.petId()), durationText(event.actualStartAt(), event.actualEndAt()));
            notificationApi.pushOnce(new NotificationPush(event.userId(), TYPE_APPOINTMENT_COMPLETED,
                    title, content, MODULE_APPOINTMENT, event.appointmentId()));
        } catch (RuntimeException e) {
            log.warn("服务完成通知旁路失败，不影响已提交的预约 appointmentId={}: {}",
                    event == null ? null : event.appointmentId(), e.getMessage());
        }
    }

    private static String contentOf(boolean boarding, String serviceName, String petName, String duration) {
        if (petName == null) {
            return boarding
                    ? "你的【" + serviceName + "】托管已办理离店，毛孩子到家后请留意它的状态，感谢信任。"
                    : "你的【" + serviceName + "】已全部服务完成，宠物可以接走了，感谢信任。";
        }
        if (boarding) {
            String base = "你家【" + petName + "】的【" + serviceName + "】已办理离店";
            return duration == null
                    ? base + "。毛孩子到家后请留意它的状态，感谢信任。"
                    : base + "，本次入住" + spaced(duration)
                            + "。毛孩子到家后请留意它的状态，感谢信任。";
        }
        if (duration == null) {
            return "你家【" + petName + "】的【" + serviceName + "】已全部服务完成，宠物可以接走了，感谢信任。";
        }
        return "你家【" + petName + "】的【" + serviceName + "】已完成。从开始服务到完成，用时"
                + spaced(duration) + "。感谢信任。";
    }

    /** "不足 1 分钟"紧贴中文，其余时长与数字间留一个空格（与现有中文排版一致）。 */
    private static String spaced(String duration) {
        return duration.startsWith("不足") ? duration : " " + duration;
    }

    /**
     * INSTANT 是开始服务到完成的真实跨度，BOARDING 是入住到离店的真实跨度。
     * 按完整分钟向下取整，与 TaskCompletionService.actualDurationMin 同口径；
     * 任一侧缺失、结束不晚于开始、负时长一律不输出，绝不补默认值。
     */
    static String durationText(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !end.isAfter(start)) {
            return null;
        }
        long totalMinutes = Duration.between(start, end).toMinutes();
        if (totalMinutes < 1) {
            return "不足 1 分钟";
        }
        long days = totalMinutes / (24 * 60);
        long hours = (totalMinutes % (24 * 60)) / 60;
        long minutes = totalMinutes % 60;
        if (days > 0) {
            StringBuilder text = new StringBuilder(days + " 天");
            if (hours > 0) {
                text.append(" ").append(hours).append(" 小时");
            }
            if (minutes > 0) {
                text.append(" ").append(minutes).append(" 分钟");
            }
            return text.toString();
        }
        if (hours > 0) {
            return minutes > 0 ? hours + " 小时 " + minutes + " 分钟" : hours + " 小时";
        }
        return minutes + " 分钟";
    }

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
            log.warn("服务完成通知读取宠物名失败，降级为无宠物名 petId={}: {}", petId, e.getMessage());
            return null;
        }
    }
}
