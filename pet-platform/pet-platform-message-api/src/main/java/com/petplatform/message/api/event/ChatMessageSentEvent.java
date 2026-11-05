package com.petplatform.message.api.event;

import java.time.LocalDateTime;

/**
 * 聊天消息落库事件（H5）：一条消息**成功新建**（{@code insertIfAbsent==1}）这一事实的广播。
 *
 * <p>发布点＝{@code ChatMessageTxService.insertAndBump} 的**事务内**（幂等命中/clientMsgId 重放
 * 一律不发布）——订阅方按 {@code @TransactionalEventListener(AFTER_COMMIT)} 消费：事务回滚则
 * 事实不存在、事件不触发。字段取自落库行，订阅方零回查主链；事件不承载任何通知业务逻辑
 * （"要不要发提醒、发给谁"由订阅方裁决）。
 */
public record ChatMessageSentEvent(
        Long conversationId,
        Long messageId,
        String senderType,
        Long senderUserId,
        String preview,
        LocalDateTime occurredAt
) {
}
