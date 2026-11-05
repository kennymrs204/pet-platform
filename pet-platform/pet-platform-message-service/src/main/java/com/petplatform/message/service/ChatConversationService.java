package com.petplatform.message.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import com.petplatform.message.api.ChatConversationApi;
import com.petplatform.message.api.ChatSide;
import com.petplatform.message.api.dto.ConversationCreateCommand;
import com.petplatform.message.api.dto.ConversationRef;
import com.petplatform.message.domain.ConversationEntity;
import com.petplatform.message.mapper.ConversationMapper;
import com.petplatform.message.mapper.MessageMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 会话读写实现（表 Owner=message）。未读采用**游标式**（last_read_message_id），
 * 无计数器列：标记已读=写"当刻最大消息 id"快照（GREATEST 原子、只前进、幂等），
 * 快照后到达的新消息 id 更大 → 必然保持未读（并发发送 × 已读不丢）。
 */
@Service
public class ChatConversationService implements ChatConversationApi {

    private static final int MAX_PAGE_SIZE = 100;

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    public ChatConversationService(ConversationMapper conversationMapper, MessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    /**
     * get-or-create：先查（快路径）→ 无则插（单条语句自动提交）→ 唯一键冲突时回查归一。
     * 冲突异常在**无事务方法**里捕获：单条 INSERT 各自提交，不存在 rollback-only 牵连；
     * 失败方重查必然可见赢家的行（对方已提交才会产生 uk 冲突）。
     */
    @Override
    public ConversationRef getOrCreate(ConversationCreateCommand command) {
        if (command == null || command.getStoreId() == null || command.getCustomerUserId() == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "storeId/customerUserId 必填");
        }
        ConversationEntity existing = conversationMapper.selectByStoreCustomer(
                command.getStoreId(), command.getCustomerUserId());
        if (existing != null) {
            return toRef(existing);
        }
        ConversationEntity entity = new ConversationEntity();
        entity.setId(IdWorker.getId());
        entity.setStoreId(command.getStoreId());
        entity.setCustomerUserId(command.getCustomerUserId());
        entity.setOriginBizModule(normalize(command.getOriginBizModule()));
        entity.setOriginBizId(normalizeBizId(command.getOriginBizModule(), command.getOriginBizId()));
        try {
            conversationMapper.insert(entity);
            return toRef(entity);
        } catch (DuplicateKeyException e) {
            ConversationEntity raced = conversationMapper.selectByStoreCustomer(
                    command.getStoreId(), command.getCustomerUserId());
            if (raced == null) {
                throw e; // 理论不可达（uk 冲突必有行）——如实上抛，不吞不伪造
            }
            return toRef(raced);
        }
    }

    @Override
    public ConversationRef conversation(Long conversationId, String side, Long actorId) {
        // 只读，不清未读（已读只由 markRead 按明确时机处理）；归属校验与全部按 id 操作同口径
        return toRef(ChatGuard.requireMine(conversationMapper, conversationId, side, actorId));
    }

    @Override
    public void markRead(Long conversationId, String side, Long actorId) {
        ConversationEntity conv = ChatGuard.requireMine(conversationMapper, conversationId, side, actorId);
        long maxMessageId = messageMapper.selectMaxId(conv.getId());
        // 0 行无害：无新消息（幂等）或 maxMessageId=0 无变化；归属已在 requireMine 校验过
        if (ChatSide.CUSTOMER.equals(side)) {
            conversationMapper.markCustomerRead(conv.getId(), actorId, maxMessageId);
        } else {
            conversationMapper.markStoreRead(conv.getId(), actorId, maxMessageId);
        }
    }

    @Override
    public PageResult<ConversationRef> customerConversations(Long customerUserId, int pageNum, int pageSize) {
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        long total = conversationMapper.countCustomer(customerUserId);
        List<ConversationEntity> rows = total == 0 ? List.of()
                : conversationMapper.selectCustomerPage(customerUserId, (page - 1) * size, size);
        return PageResult.of(total, page, size, rows.stream().map(this::toRef).toList());
    }

    @Override
    public PageResult<ConversationRef> storeConversations(Long storeId, int pageNum, int pageSize) {
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        long total = conversationMapper.countStore(storeId);
        List<ConversationEntity> rows = total == 0 ? List.of()
                : conversationMapper.selectStorePage(storeId, (page - 1) * size, size);
        return PageResult.of(total, page, size, rows.stream().map(this::toRef).toList());
    }

    /** H1：本店全量 refs（不分页；排序同分页列表）——搜索过滤/分页由 app 层编排。 */
    @Override
    public List<ConversationRef> allStoreConversations(Long storeId) {
        if (storeId == null) {
            return List.of();
        }
        return conversationMapper.selectStoreAll(storeId).stream().map(this::toRef).toList();
    }

    @Override
    public long customerUnreadTotal(Long customerUserId) {
        return messageMapper.customerUnreadTotal(customerUserId);
    }

    @Override
    public long storeUnreadTotal(Long storeId) {
        return messageMapper.storeUnreadTotal(storeId);
    }

    @Override
    public Map<Long, Long> customerUnreadByConversation(Long customerUserId) {
        return messageMapper.customerUnreadByConversation(customerUserId).stream()
                .collect(Collectors.toMap(MessageMapper.UnreadRow::getConversationId, MessageMapper.UnreadRow::getUnread));
    }

    @Override
    public Map<Long, Long> storeUnreadByConversation(Long storeId) {
        return messageMapper.storeUnreadByConversation(storeId).stream()
                .collect(Collectors.toMap(MessageMapper.UnreadRow::getConversationId, MessageMapper.UnreadRow::getUnread));
    }

    private static String normalize(String bizModule) {
        return bizModule == null || bizModule.isBlank() ? null : bizModule.trim();
    }

    /** bizId 只在 bizModule 同时存在时保留——裸 id 没有可解释的语义（弱引用成对出现）。 */
    private static Long normalizeBizId(String bizModule, Long bizId) {
        return normalize(bizModule) == null ? null : bizId;
    }

    private ConversationRef toRef(ConversationEntity e) {
        return ConversationRef.builder()
                .id(e.getId()).storeId(e.getStoreId()).customerUserId(e.getCustomerUserId())
                .originBizModule(e.getOriginBizModule()).originBizId(e.getOriginBizId())
                .lastMessageId(e.getLastMessageId()).lastMessageAt(e.getLastMessageAt())
                .lastMessagePreview(e.getLastMessagePreview())
                .customerLastReadMessageId(e.getCustomerLastReadMessageId())
                .storeLastReadMessageId(e.getStoreLastReadMessageId())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
