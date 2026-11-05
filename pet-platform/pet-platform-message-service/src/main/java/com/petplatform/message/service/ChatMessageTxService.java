package com.petplatform.message.service;

import com.petplatform.message.api.event.ChatMessageSentEvent;
import com.petplatform.message.domain.MessageEntity;
import com.petplatform.message.mapper.ConversationMapper;
import com.petplatform.message.mapper.MessageMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发送消息的事务段（独立 bean——自调用会绕过 @Transactional 代理）。
 * 同事务：消息插入 → 会话最近消息摘要推进 → **消息落库事件广播（H5）**。任一步失败整体回滚——
 * "消息插了但摘要没更新"（或反之）不可能出现。
 *
 * <p>幂等命中（insertIfAbsent 0 行）时**不动会话、不发布事件**：重复提交不该刷新摘要与排序位置，
 * 也不该让订阅方（H5 门店提醒）收到第二次广播——这是强不变量。
 * 数据库异常一律上抛（调用方在外层非事务环境兜底回查），不在此吞。
 */
@Service
public class ChatMessageTxService {

    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final ApplicationEventPublisher events;

    public ChatMessageTxService(MessageMapper messageMapper, ConversationMapper conversationMapper,
                                ApplicationEventPublisher events) {
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.events = events;
    }

    /**
     * @return true = 新消息已插入（摘要已推进、事件已广播）；false = 幂等命中（调用方回查原消息返回）
     */
    @Transactional
    public boolean insertAndBump(MessageEntity message, String preview) {
        if (messageMapper.insertIfAbsent(message) == 0) {
            return false;
        }
        // 条件更新（last_message_id < 新 id 才覆盖）：并发发送中小 id 后提交者不会把摘要倒回旧消息
        conversationMapper.bumpLastMessage(message.getConversationId(), message.getId(),
                message.getCreatedAt(), preview);
        // H5：事件必须在**事务内**发布（订阅方用 AFTER_COMMIT——事务外发布监听器不触发）；
        // 且只在"真插入"路径发布（上方幂等命中已提前返回，重放绝不重复广播）
        events.publishEvent(new ChatMessageSentEvent(message.getConversationId(), message.getId(),
                message.getSenderType(), message.getSenderUserId(), preview, message.getCreatedAt()));
        return true;
    }
}
