package com.petplatform.app.listener;

import com.petplatform.boarding.api.event.BoardingCareAbnormalEvent;
import com.petplatform.notification.api.NotificationApi;
import com.petplatform.notification.api.dto.NotificationPush;
import com.petplatform.pet.api.PetQueryApi;
import com.petplatform.pet.api.dto.PetSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 照护异常消息桥（ADR-047）：{@link BoardingCareAbnormalEvent} 自 ADR-015 §5 建立起<b>全仓零消费者</b>
 * ——事件 javadoc 自己写着"notification 段接入前无消费者，禁止 boarding 直写 ntf_notification"，
 * 本片就是那个"notification 段"的接入点。 boarding 域的事件形状、发布点、入住单状态机一字未动。
 *
 * <p><b>收件人=能写护理记录的人</b>（{@code boarding:care:create}，实测 V2 授予店长与护理员），
 * 而不是"全店都有 {@code boarding:care:view}"——V4 把 view 发给了全部 7 个员工角色，
 * 用它当判据等于给美容师、前台、医生各推一条别人的照护异常，提醒变噪音（判据取自 {@link StoreMessageRecipients}，
 * 角色归属由 {@code emp_role_permission} 现算）。
 *
 * <p><b>用 {@code push} 而不是 {@code pushOnce}</b>（ADR-045 定下的分工）：同一次住单<b>每天</b>都可能
 * 新出一条异常记录，去重键 {@code (收件人, 门店, CARE_ABNORMAL, store_stay:stayId)} 只有住单粒度，
 * 用 pushOnce 会把第二天的异常静默吞掉——那正是"店里有异常但没人被通知"的事故形态。
 * 幂等只给一次性事实，可重复发生的事实必须允许多条（与退款申请同族）。
 *
 * <p>AFTER_COMMIT（异常记录落库的事务内发布，同步监听会在回滚时留下假消息）+ 整段吞 warn（通知是旁路，
 * 绝不能让"消息写不进去"变成"护理记录保存失败"）。
 */
@Slf4j
@Component
public class BoardingCareAbnormalNotificationListener {

    /** V1 {@code ntf_notification.type} 列注释里就预留了这个类型名，不另造。 */
    static final String TYPE_CARE_ABNORMAL = "CARE_ABNORMAL";
    /** B 端据此跳 {@code /boarding?openId={bizId}}（实测 BoardingPage 的展开键就是住单 id）；C 端不认 ⇒ 只读不可跳。 */
    static final String MODULE_STORE_STAY = "store_stay";
    static final String CODE_RECIPIENT = "boarding:care:create";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日");

    private final PetQueryApi petQueryApi;
    private final StoreMessageRecipients recipients;
    private final NotificationApi notificationApi;

    public BoardingCareAbnormalNotificationListener(PetQueryApi petQueryApi,
                                                   StoreMessageRecipients recipients,
                                                   NotificationApi notificationApi) {
        this.petQueryApi = petQueryApi;
        this.recipients = recipients;
        this.notificationApi = notificationApi;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCareAbnormal(BoardingCareAbnormalEvent event) {
        try {
            if (event == null || event.boardingStayId() == null || event.storeId() == null) {
                return;
            }
            List<Long> userIds = recipients.userIdsHolding(event.storeId(), CODE_RECIPIENT);
            if (userIds.isEmpty()) {
                log.debug("照护异常消息跳过：该店无人持有 {} storeId={}", CODE_RECIPIENT, event.storeId());
                return;
            }
            String content = contentOf(event);
            for (Long userId : userIds) {
                notificationApi.push(new NotificationPush(userId, event.storeId(), TYPE_CARE_ABNORMAL,
                        "照护记录有异常", content, MODULE_STORE_STAY, event.boardingStayId()));
            }
        } catch (RuntimeException e) {
            log.warn("照护异常消息旁路失败，不影响已提交的护理记录 boardingStayId={}: {}",
                    event == null ? null : event.boardingStayId(), e.getMessage());
        }
    }

    /**
     * 只说库里成立的事：哪天、哪只宠物、被标记了异常。<b>不复述异常内容</b>——事件里没有描述字段，
     * 而把护理记录的正文抄进消息会造出第二个副本（记录被改后消息就是假话，同 ADR-041 §4 不搬内容的理由）。
     * 细节让人点回当日记录看，这也是 {@code store_stay} 跳转存在的意义。
     */
    private String contentOf(BoardingCareAbnormalEvent event) {
        String pet = petName(event.petId());
        StringBuilder sb = new StringBuilder();
        sb.append(pet == null ? "住单" : "【" + pet + "】");
        sb.append(event.careDate() == null ? "的照护记录" : event.careDate().format(DAY) + " 的照护记录")
                .append("被标记为异常，请回看当日护理记录。");
        return sb.toString();
    }

    /**
     * 宠物名是<b>可选</b>正文摘要：查询抛运行时异常时降级为无名（B3 同款独立容错边界），
     * 由 {@link #contentOf} 回退"住单…"基础文案。contentOf 在收件人循环<b>之前</b>执行，
     * 此处不容错的话一次摘要异常会让<b>全部</b>持码收件人都收不到照护异常提醒——比多发一条严重得多。
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
            log.warn("照护异常消息读取宠物名失败，降级为无宠物名 petId={}: {}", petId, e.getMessage());
            return null;
        }
    }
}
