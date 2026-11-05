package com.petplatform.message.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.storage.MediaStorage;
import com.petplatform.message.api.ChatMessageApi;
import com.petplatform.message.api.ChatMessageType;
import com.petplatform.message.api.ChatSide;
import com.petplatform.message.api.dto.MessagePage;
import com.petplatform.message.api.dto.MessageRef;
import com.petplatform.message.api.dto.MessageSendCommand;
import com.petplatform.message.domain.MessageEntity;
import com.petplatform.message.mapper.ConversationMapper;
import com.petplatform.message.mapper.MessageMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 消息读写实现（表 Owner=message）。
 *
 * <p>发送的三层幂等（缺一不可）：
 * <ol>
 *   <li>快路径 SELECT by (conversation_id, client_msg_id) —— 覆盖正常重试；</li>
 *   <li>事务段内 insertIfAbsent（NOT EXISTS 条件插入）—— 覆盖"查询后、插入前"到达的重复；</li>
 *   <li>表级 uk_conv_client 唯一约束 + 冲突回查 —— 覆盖真并发（两个请求同时通过前两层检查）。
 *       冲突异常在**无事务的外层**捕获后重查：此时赢家已提交，重查必然可见（不吞不伪造）。</li>
 * </ol>
 * 重复提交一律返回**原消息**（幂等成功语义），不是报错。
 */
@Service
public class ChatMessageService implements ChatMessageApi {

    private static final int CONTENT_MAX = 500;
    private static final int CLIENT_ID_MAX = 64;
    private static final int PREVIEW_MAX = 100;
    private static final int DEFAULT_PAGE = 20;
    private static final int MAX_PAGE = 50;
    /** IMAGE 消息的固定 content（摘要/列表/旧客户端全走纯文本管道，零分支）。 */
    private static final String IMAGE_CONTENT = "[图片]";

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ChatMessageTxService txService;
    private final ObjectProvider<MediaStorage> media;
    private final long maxImageBytes;

    public ChatMessageService(ConversationMapper conversationMapper, MessageMapper messageMapper,
                              ChatMessageTxService txService,
                              ObjectProvider<MediaStorage> media,
                              @Value("${pet.storage.max-size-mb:5}") long maxSizeMb) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.txService = txService;
        this.media = media;
        this.maxImageBytes = maxSizeMb * 1024 * 1024;
    }

    @Override
    public MessageRef send(MessageSendCommand command) {
        if (command == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "请求体必填");
        }
        // 消息类型：空=TEXT（旧客户端零改动）；非 TEXT/IMAGE 一律 10001
        String messageType = command.getMessageType() == null ? "" : command.getMessageType().trim();
        if (messageType.isEmpty()) {
            messageType = ChatMessageType.TEXT;
        }
        if (!ChatMessageType.TEXT.equals(messageType) && !ChatMessageType.IMAGE.equals(messageType)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "不支持的消息类型: " + messageType);
        }
        boolean image = ChatMessageType.IMAGE.equals(messageType);
        String content = command.getContent() == null ? "" : command.getContent().trim();
        String mediaKey = command.getMediaKey() == null ? "" : command.getMediaKey().trim();
        if (image) {
            // 图片消息契约：content 固定 '[图片]'（摘要/列表/旧客户端全走纯文本管道）；
            // mediaKey 三道校验缺一不可——尤其"桶主==发送者"：读取侧会按消息行签名，
            // 不校归属＝让发送者决定"替谁的对象签名"（ADR-029 §4 教训的聊天版 IDOR）。
            if (!IMAGE_CONTENT.equals(content)) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "图片消息 content 固定为 [图片]");
            }
            if (mediaKey.isEmpty()) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "图片消息 mediaKey 必填");
            }
            if (!MediaStorage.isValidKey(mediaKey)) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "mediaKey 形态非法");
            }
            if (!MediaStorage.SCENE_CHAT_IMAGE.equals(MediaStorage.keyScene(mediaKey))) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "mediaKey 场景非法");
            }
            if (!Objects.equals(command.getSenderUserId(), MediaStorage.keyOwner(mediaKey))) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "mediaKey 不属于发送者");
            }
        } else {
            if (content.isEmpty()) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "消息内容不能为空");
            }
            if (content.length() > CONTENT_MAX) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "消息不超过 500 字");
            }
            if (!mediaKey.isEmpty()) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "文本消息不能携带 mediaKey");
            }
        }
        String clientMsgId = command.getClientMsgId() == null ? "" : command.getClientMsgId().trim();
        if (clientMsgId.isEmpty() || clientMsgId.length() > CLIENT_ID_MAX) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "clientMsgId 必填且不超过 64 字符");
        }
        // 归属校验（10004）：CUSTOMER 按 senderUserId 匹配 customer_user_id；
        // STORE 按 senderStoreId 匹配 store_id（senderUserId 只是落库的真实发送人，不参与归属判定）
        Long scopeId = ChatSide.isStore(command.getSenderType()) ? command.getSenderStoreId() : command.getSenderUserId();
        ChatGuard.requireMine(conversationMapper, command.getConversationId(), command.getSenderType(), scopeId);

        MessageEntity existing = messageMapper.selectByClientMsgId(command.getConversationId(), clientMsgId);
        if (existing != null) {
            return toRef(existing);
        }
        // H2（P7 遗留）：对象回查只在**新建路径**做——幂等重放直接返回原消息，不依赖对象仍存在。
        MediaStorage.StoredObject stored = image ? requireStoredImage(mediaKey) : null;
        MessageEntity message = new MessageEntity();
        message.setId(IdWorker.getId());
        message.setConversationId(command.getConversationId());
        message.setSenderType(command.getSenderType());
        message.setSenderUserId(command.getSenderUserId());
        message.setSenderNameSnapshot(senderName(command));
        message.setMessageType(messageType);
        // IMAGE 落库统一写常量（入参已逐字校验=[图片]），保证落库口径单一
        message.setContent(image ? IMAGE_CONTENT : content);
        message.setMediaKey(image ? mediaKey : null);
        message.setMediaContentType(image ? contentTypeOf(mediaKey) : null);
        // media_size：发送时 HEAD 回查的真实字节数（H2）——"消息必有图可看"的写入闸配套落值
        message.setMediaSize(stored == null ? null : stored.size());
        message.setBizModule(normalize(command.getBizModule()));
        message.setBizId(normalize(command.getBizModule()) == null ? null : command.getBizId());
        message.setClientMsgId(clientMsgId);
        // 应用时钟写入（实体带回给摘要推进，两行时间来自同一值；容器 TZ 与库同为 +08:00，项目既有口径）
        message.setCreatedAt(LocalDateTime.now());
        try {
            if (txService.insertAndBump(message, preview(message.getContent()))) {
                return toRef(message);
            }
            return requireByClientMsgId(command.getConversationId(), clientMsgId);
        } catch (DuplicateKeyException e) {
            return requireByClientMsgId(command.getConversationId(), clientMsgId);
        }
    }

    @Override
    public MessagePage messages(Long conversationId, String side, Long actorId, Long beforeId, Long afterId, Integer limit) {
        if (beforeId != null && afterId != null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "beforeId 与 afterId 不能同时传入");
        }
        ChatGuard.requireMine(conversationMapper, conversationId, side, actorId);
        int size = limit == null ? DEFAULT_PAGE : Math.min(Math.max(limit, 1), MAX_PAGE);
        // 多取一条判 hasMore：DESC 方向=更早还有；ASC 方向（afterId）=更新还有——
        // 调用方以本页最后一条 id 续拉，单调前进，不会重复同一页
        List<MessageEntity> rows = messageMapper.selectPage(conversationId, beforeId, afterId, size + 1);
        boolean hasMore = rows.size() > size;
        List<MessageRef> list = rows.stream().limit(size).map(ChatMessageService::toRef).toList();
        return MessagePage.builder().list(list).hasMore(hasMore).build();
    }

    /** H5（T1）：门店视角该会话最小未读客户消息 id（无未读→null）——门店提醒"未读段首条"判据。 */
    @Override
    public Long minUnreadCustomerMessageId(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        return messageMapper.selectMinUnreadCustomerId(conversationId);
    }

    /**
     * H2（P7 遗留）：IMAGE 发送时对对象做 HEAD 回查——存在性 / 非空 / 真实大小 ≤ 上限。
     *
     * <p>读取侧是签名直发，不校验字节；这里是"消息必有图可看"的写入闸：key 形态/归属都对
     * 但对象缺失（PUT 丢包、伪造调用）时，绝不让坏消息落库。大小上限与上传 intent 同旋钮
     * （{@code pet.storage.max-size-mb}）——presigned PUT 不绑定体积，本闸是超限对象的真兜底。
     *
     * <p>降级口径：存储未启用 → 10001（无存储的环境本就不可能有合法图片消息）；
     * 回查基础设施异常 → 10009（可重试，不落坏消息）。
     */
    private MediaStorage.StoredObject requireStoredImage(String mediaKey) {
        MediaStorage storage = media.getIfAvailable();
        if (storage == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "图片存储未启用，无法发送图片");
        }
        MediaStorage.StoredObject obj;
        try {
            obj = storage.stat(mediaKey);
        } catch (RuntimeException e) {
            throw new BusinessException(CommonErrorCode.SYSTEM_ERROR, "图片存储暂不可用，请稍后重试");
        }
        if (obj == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "图片未上传成功，请重新选择图片后发送");
        }
        if (obj.size() <= 0) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "图片文件为空，请重新选择图片后发送");
        }
        if (obj.size() > maxImageBytes) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID,
                    "图片超过大小上限（" + (maxImageBytes / 1024 / 1024) + "MB）");
        }
        return obj;
    }

    /** 幂等命中后的回查：行必然存在（0 行/NOT EXISTS 或 uk 冲突的前提都是"已有"），拿不到=真异常。 */
    private MessageRef requireByClientMsgId(Long conversationId, String clientMsgId) {
        MessageEntity raced = messageMapper.selectByClientMsgId(conversationId, clientMsgId);
        if (raced == null) {
            throw new BusinessException(CommonErrorCode.DUPLICATE_REQUEST, "消息提交冲突，请重试");
        }
        return toRef(raced);
    }

    private static String senderName(MessageSendCommand command) {
        String name = command.getSenderName() == null ? "" : command.getSenderName().trim();
        if (!name.isEmpty()) {
            return name.length() > 50 ? name.substring(0, 50) : name;
        }
        return ChatSide.isStore(command.getSenderType()) ? "门店" : "顾客";
    }

    private static String preview(String content) {
        return content.length() <= PREVIEW_MAX ? content : content.substring(0, PREVIEW_MAX);
    }

    private static String normalize(String bizModule) {
        return bizModule == null || bizModule.isBlank() ? null : bizModule.trim();
    }

    /** IMAGE 消息的 MIME：由已过形态校验的 key 扩展名反推（服务端口径，不采信客户端声明）。 */
    private static String contentTypeOf(String key) {
        return switch (key.substring(key.lastIndexOf('.') + 1)) {
            case "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> null; // 形态校验已保证不可达
        };
    }

    private static MessageRef toRef(MessageEntity e) {
        return MessageRef.builder()
                .id(e.getId()).conversationId(e.getConversationId())
                .senderType(e.getSenderType()).senderUserId(e.getSenderUserId())
                .senderNameSnapshot(e.getSenderNameSnapshot()).content(e.getContent())
                .messageType(e.getMessageType()).mediaKey(e.getMediaKey())
                .bizModule(e.getBizModule()).bizId(e.getBizId())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
