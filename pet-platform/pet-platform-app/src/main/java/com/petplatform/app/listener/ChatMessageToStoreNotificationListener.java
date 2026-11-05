package com.petplatform.app.listener;

import com.petplatform.message.api.ChatConversationApi;
import com.petplatform.message.api.ChatMessageApi;
import com.petplatform.message.api.ChatSide;
import com.petplatform.message.api.dto.ConversationRef;
import com.petplatform.message.api.event.ChatMessageSentEvent;
import com.petplatform.notification.api.NotificationApi;
import com.petplatform.notification.api.dto.NotificationPush;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 门店侧「客户来消息了」提醒桥（H5）：客户发来消息，<b>店里该有人知道</b>。
 *
 * <p><b>T1＝每个会话每个"未读段"一条提醒</b>：本消息必须恰是"当前最小未读客户消息 id"
 * （口径与 storeUnread* 同源，{@code ChatMessageApi#minUnreadCustomerMessageId}）——
 * 不是每条客户消息一条（噪音），也不是每会话永久一条（弱）。门店读完该会话后，下一条客户消息
 * 重新构成未读段、可再次提醒；串行发送时一个未读段恒只提醒一次。
 *
 * <p><b>并发逆序提交</b>：A/B 两条消息若 B 先提交、A 后提交，两个 AFTER_COMMIT 监听器会分别
 * 看到 minUnread=B / minUnread=A，仅靠下面 bizId 去重会写出两条。该竞态由
 * {@code NotificationService.pushOnce} 的 chat 分支兜底：按 conversationId 取 MySQL 命名锁，
 * 锁内把已有未读提醒的 bizId 规范化为真正的段首，锁在事务完成后释放（多实例安全）。
 *
 * <p><b>幂等分两层</b>：biz_ref＝{@code {module:"chat", bizId:首条未读 messageId,
 * conversationId:会话 id}}——pushOnce 的 bizId 条件插入负责重放/同段串行去重，
 * 会话锁负责并发逆序提交；conversationId 只作前端深链定位键（消息中心点击进对应会话）。
 *
 * <p><b>AFTER_COMMIT + 整段吞 warn</b>：消息落库事件只在事务提交后可见——回滚则没有提醒；
 * 通知是旁路，异常绝不能反向影响已提交的消息主链（同 ADR-045/046/047/052 一贯纪律）。
 * 收件人＝{@link StoreMessageRecipients} 按 {@code store:chat:view} 现算（店长/前台 +
 * 平台管理员；ADMIN 全域收件是既有规则，不在本片重新设计）。
 */
@Slf4j
@Component
public class ChatMessageToStoreNotificationListener {

    /** 门店侧聊天提醒类型（与客户侧消息类型分列；Package 1：不计入 notificationCount）。 */
    static final String TYPE_CHAT_MESSAGE = "CHAT_MESSAGE";
    /** biz_ref 的 module：b-web 据此跳 {@code /customer-chat?open={conversationId}}。 */
    static final String MODULE_CHAT = "chat";
    /** 收件人判据＝能打开会话的人（V11 授 STORE_MANAGER + RECEPTIONIST，V12 授 ADMIN）。 */
    static final String CODE_RECIPIENT = "store:chat:view";

    static final String TITLE = "客户消息";
    static final String CONTENT = "客户发来新的消息，请及时回复";

    private final ChatConversationApi chatConversationApi;
    private final ChatMessageApi chatMessageApi;
    private final StoreMessageRecipients recipients;
    private final NotificationApi notificationApi;

    public ChatMessageToStoreNotificationListener(ChatConversationApi chatConversationApi,
                                                  ChatMessageApi chatMessageApi,
                                                  StoreMessageRecipients recipients,
                                                  NotificationApi notificationApi) {
        this.chatConversationApi = chatConversationApi;
        this.chatMessageApi = chatMessageApi;
        this.recipients = recipients;
        this.notificationApi = notificationApi;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChatMessageSent(ChatMessageSentEvent event) {
        try {
            if (event == null || event.conversationId() == null || event.messageId() == null
                    || event.senderUserId() == null) {
                return;
            }
            // 只有客户的消息才提醒门店；门店员工发消息本轮不产生任何提醒（C 端对称提醒未做）
            if (!ChatSide.isCustomer(event.senderType())) {
                return;
            }
            ConversationRef conv = chatConversationApi.conversation(event.conversationId(),
                    ChatSide.CUSTOMER, event.senderUserId());
            if (conv == null || conv.getStoreId() == null) {
                log.warn("门店聊天提醒跳过：读不到会话 conversationId={}", event.conversationId());
                return;
            }
            // T1：本消息必须是"当前未读段首条"才提醒（连发 A/B/C 只提醒 A；读后 D 重新起段）
            Long minUnread = chatMessageApi.minUnreadCustomerMessageId(event.conversationId());
            if (minUnread == null || !minUnread.equals(event.messageId())) {
                log.debug("门店聊天提醒跳过：非未读段首条 conversationId={} minUnread={} messageId={}",
                        event.conversationId(), minUnread, event.messageId());
                return;
            }
            List<Long> userIds = recipients.userIdsHolding(conv.getStoreId(), CODE_RECIPIENT);
            if (userIds.isEmpty()) {
                // 店里没人能看会话 ⇒ 不发；通知是旁路，编不出收件人时宁可少一条
                log.debug("门店聊天提醒跳过：该店无人持有 {} storeId={}", CODE_RECIPIENT, conv.getStoreId());
                return;
            }
            for (Long userId : userIds) {
                notificationApi.pushOnce(new NotificationPush(userId, conv.getStoreId(), TYPE_CHAT_MESSAGE,
                        TITLE, CONTENT, MODULE_CHAT, event.messageId(), event.conversationId()));
            }
        } catch (RuntimeException e) {
            // AFTER_COMMIT 里抛异常会传播给刚提交成功的发送请求——整段吞，绝不影响消息主链
            log.warn("门店聊天提醒旁路失败，不影响已落库的消息 conversationId={}: {}",
                    event == null ? null : event.conversationId(), e.getMessage());
        }
    }
}
