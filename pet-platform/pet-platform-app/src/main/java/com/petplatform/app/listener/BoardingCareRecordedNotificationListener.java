package com.petplatform.app.listener;

import com.petplatform.boarding.api.event.BoardingCareRecordedEvent;
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
 * 护理记录顾客消息桥（ADR-015 §5 补记 2026-10-27，用户拍板 b 案：每条记录都推）。
 * 骨架同 {@link BoardingCareAbnormalNotificationListener}：AFTER_COMMIT + 整段吞 warn——通知是旁路，
 * 绝不能让"消息写不进去"变成"护理记录保存失败"。
 *
 * <p><b>用 {@code push} 而不是 {@code pushOnce}</b>：拍板口径就是"每条都推"（一天多条记录是
 * ADR-015 §5 的既定设计），幂等去重会静默吞掉第二条起的通知，与拍板语义相反。
 *
 * <p>收件人 = 事件携带的 ownerId（发布前经 {@code ownerIdOfAppointment} 解析；null/≤0 静默跳过）。
 * C 端 {@code /boarding/{bizId}} 是主人态读口，非主人打不开，转发无泄露面。
 */
@Slf4j
@Component
public class BoardingCareRecordedNotificationListener {

    /** 新类型：V1 type 列是自由 varchar（CARE_ABNORMAL 同例零迁移）。 */
    static final String TYPE_CARE_RECORDED = "BOARDING_CARE";
    /** C 端据此跳 {@code /boarding/{bizId}}（顾客托管详情/日报）。 */
    static final String MODULE_BOARDING = "boarding";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日");

    private final PetQueryApi petQueryApi;
    private final NotificationApi notificationApi;

    public BoardingCareRecordedNotificationListener(PetQueryApi petQueryApi, NotificationApi notificationApi) {
        this.petQueryApi = petQueryApi;
        this.notificationApi = notificationApi;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCareRecorded(BoardingCareRecordedEvent event) {
        try {
            if (event == null || event.boardingStayId() == null || event.storeId() == null) {
                return;
            }
            Long ownerId = event.ownerId();
            if (ownerId == null || ownerId <= 0) {
                return;
            }
            // 顾客消息走 6 参构造（storeId=0=个人收件箱）：带 storeId 会落进 B 端门店收件箱，
            // C 端 pageForPersonalInbox 按 store_id=0 过滤就永远看不到——首版踩过这个坑（真实验收抓出）。
            notificationApi.push(new NotificationPush(ownerId, TYPE_CARE_RECORDED,
                    "护理记录更新", contentOf(event), MODULE_BOARDING, event.boardingStayId()));
        } catch (RuntimeException e) {
            log.warn("护理记录顾客消息旁路失败，不影响已提交的护理记录 boardingStayId={}: {}",
                    event == null ? null : event.boardingStayId(), e.getMessage());
        }
    }

    /** 只说"哪天、哪只宠物、有新记录"，不搬正文（同 CARE_ABNORMAL：记录被改后消息就是假话）。 */
    private String contentOf(BoardingCareRecordedEvent event) {
        String pet = petName(event.petId());
        StringBuilder sb = new StringBuilder();
        sb.append(pet == null ? "您的宠物" : "【" + pet + "】");
        sb.append(event.careDate() == null ? "" : event.careDate().format(DAY) + " ");
        sb.append("的护理记录已更新");
        if (event.abnormal()) {
            sb.append("（当日有异常上报，门店会跟进处理）");
        }
        sb.append("，详情见托管详情页。");
        return sb.toString();
    }

    /** 宠物名可选摘要：查询异常降级为无名，绝不让摘要失败吞掉整条通知。 */
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
            log.warn("护理记录顾客消息宠物名摘要失败 petId={}: {}", petId, e.getMessage());
            return null;
        }
    }
}
