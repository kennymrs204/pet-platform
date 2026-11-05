package com.petplatform.app.listener;

import com.petplatform.appointment.api.event.AppointmentAssignedEvent;
import com.petplatform.appointment.api.event.AppointmentCreatedEvent;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.notification.api.NotificationApi;
import com.petplatform.notification.api.dto.NotificationPush;
import com.petplatform.store.api.StoreQueryApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 执行人"来活了"消息桥（ADR-052）。此前的通知链有两个盲区，都被用户实测踩中：
 * <ul>
 *   <li>顾客下单时**点名**了执行人（allowUserPickEmployee）——消息只发给顾客（ADR-045）与
 *       持到店签到权的店长/前台（ADR-047），被点名的员工本人毫无感知；</li>
 *   <li>店长指派/改派执行人（ADR-048）——同样没有任何通知给被执行的人。</li>
 * </ul>
 * 本监听器补上第三类收件人：**被执行人**。两个入口共用一条推送：
 * <ul>
 *   <li>{@link AppointmentCreatedEvent}：创建事件现在携带 employeeId（ADR-052 扩展），
 *       非空 = 顾客点名 → 文案说清"支付确认后才会出现在你的任务里"（此刻订单还是待支付，
 *       与 ADR-045 的时点纪律同源，绝不提前说"已确认"）；</li>
 *   <li>{@link AppointmentAssignedEvent}：指派 CAS 落定（changed=true）后发布 → 文案是"已确认"语义。</li>
 * </ul>
 *
 * <p>收件人解析：事件里只有 emp_employee.id，站内信收件人是 usr_user.id——
 * 经 {@link EmployeeQueryApi#userIdsByEmployeeIds} 换算（仅 ACTIVE 员工；查无者静默跳过，
 * 宁可少一条消息也不发往悬空 id）。门店收件箱归 @{code storeId}=产生订单的门店（ADR-047 语义），
 * 多店员工不会把两店的"来活了"糊成一个红点。
 *
 * <p>幂等：{@link NotificationApi#pushOnce}，同一 (执行人, APPOINTMENT_ASSIGNED, appointment:id) 只落一条——
 * 改派回同一人不堆消息；AFTER_COMMIT + 整段吞异常（数据已提交，消息旁路不能反向炸到调用方）。
 */
@Slf4j
@Component
public class AppointmentExecutorNotificationListener {

    static final String TYPE_APPOINTMENT_ASSIGNED = "APPOINTMENT_ASSIGNED";
    /** B 端点击跳转模块（b-web notificationLink：store_task → /tasks，ADR-052）。 */
    static final String MODULE_STORE_TASK = "store_task";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日");

    private final EmployeeQueryApi employeeQueryApi;
    private final StoreQueryApi storeQueryApi;
    private final NotificationApi notificationApi;

    public AppointmentExecutorNotificationListener(EmployeeQueryApi employeeQueryApi,
                                                   StoreQueryApi storeQueryApi,
                                                   NotificationApi notificationApi) {
        this.employeeQueryApi = employeeQueryApi;
        this.storeQueryApi = storeQueryApi;
        this.notificationApi = notificationApi;
    }

    /** 顾客点名：此刻待支付，文案只说"提交成功"，不说"已确认"。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAppointmentCreated(AppointmentCreatedEvent event) {
        if (event == null || event.employeeId() == null) {
            return;
        }
        notifyExecutor(event.storeId(), event.employeeId(), event.appointmentId(), event.serviceName(),
                event.serviceDate(), event.startTime(),
                "顾客点名了你：一单【" + event.serviceName() + "】已提交（" + whenText(event.serviceDate(), event.startTime())
                        + " 到店）。支付确认后会出现在你的「我的任务」里，请留意排班。",
                null);
    }

    /** 店长指派/改派：单已确认，"来活了"给新执行人；改派时原执行人收"任务已调整"（ADR-053）。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAppointmentAssigned(AppointmentAssignedEvent event) {
        if (event == null || event.employeeId() == null) {
            return;
        }
        notifyExecutor(event.storeId(), event.employeeId(), event.appointmentId(), event.serviceName(),
                event.serviceDate(), event.startTime(),
                "店长把一单【" + event.serviceName() + "】派给了你：" + whenText(event.serviceDate(), event.startTime())
                        + " 到店，请按时履约；完成后在任务里提交记录。",
                null);
        Long old = event.oldEmployeeId();
        if (old != null && !old.equals(event.employeeId())) {
            notifyExecutor(event.storeId(), old, event.appointmentId(), event.serviceName(),
                    event.serviceDate(), event.startTime(),
                    "你原定的一单【" + event.serviceName() + "】（" + whenText(event.serviceDate(), event.startTime())
                            + "）已由门店调整为其他同事负责，请以最新任务安排为准。",
                    "APPOINTMENT_REASSIGNED");
        }
    }

    /** typeOverride：null=新执行人"来活了"（APPOINTMENT_ASSIGNED）；改派通知用 APPOINTMENT_REASSIGNED 区分类型与标题。 */
    private void notifyExecutor(Long storeId, Long employeeId, Long appointmentId,
                        String serviceName, LocalDate serviceDate, java.time.LocalTime startTime,
                        String content, String typeOverride) {
        try {
            var userIds = employeeQueryApi.userIdsByEmployeeIds(List.of(employeeId));
            Long userId = userIds.get(employeeId);
            if (userId == null) {
                // 员工行不在/已离职：宁可少一条消息，也不发往悬空收件人
                log.warn("执行人通知跳过：解析不到 ACTIVE 员工的登录主体 employeeId={}", employeeId);
                return;
            }
            String store = storeName(storeId);
            String full = content + (store == null ? "" : " 门店：" + store + "。");
            notificationApi.pushOnce(new NotificationPush(userId, storeId,
                    typeOverride == null ? TYPE_APPOINTMENT_ASSIGNED : typeOverride,
                    typeOverride == null ? "新任务指派" : "任务安排调整",
                    full, MODULE_STORE_TASK, appointmentId));
        } catch (RuntimeException e) {
            log.warn("执行人通知旁路失败，不影响已提交的预约 appointmentId={}: {}", appointmentId, e.getMessage());
        }
    }

    private String storeName(Long storeId) {
        if (storeId == null) {
            return null;
        }
        var names = storeQueryApi.storeNames(List.of(storeId));
        return names == null ? null : names.get(storeId);
    }

    private static String whenText(LocalDate date, java.time.LocalTime time) {
        if (date == null) {
            return "时间待定";
        }
        return time == null ? date.format(DAY) : date.format(DAY) + " " + time;
    }
}
