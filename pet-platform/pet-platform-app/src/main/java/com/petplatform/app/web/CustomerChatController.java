package com.petplatform.app.web;

import com.petplatform.app.service.ChatMediaSigner;
import com.petplatform.appointment.api.AppointmentQueryApi;
import com.petplatform.appointment.api.dto.AppointmentSelfView;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.message.api.ChatConversationApi;
import com.petplatform.message.api.ChatMessageApi;
import com.petplatform.message.api.ChatSide;
import com.petplatform.message.api.dto.ConversationCreateCommand;
import com.petplatform.message.api.dto.ConversationRef;
import com.petplatform.message.api.dto.MessagePage;
import com.petplatform.message.api.dto.MessageRef;
import com.petplatform.message.api.dto.MessageSendCommand;
import com.petplatform.notification.api.NotificationQueryApi;
import com.petplatform.store.api.StoreQueryApi;
import com.petplatform.user.api.UserAccountApi;
import com.petplatform.user.api.dto.UserProfileView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * C 端门店沟通（会话/消息/未读 + 通知×聊天合计未读）。
 *
 * <p>归属只从鉴权身份取（{@code principal.userId()}）：请求体里**没有 customerUserId**——
 * 会话所有者不可被调用方指定；会话级归属复核在 message-service 的 ChatGuard（防御深度）。
 * 业务校验（门店存在、业务关联归属）在本层（app 装配根，跨模块编排的唯一合法位置）。
 *
 * <p>门店状态口径：创建/复用会话**不校验门店 OPEN**——已停业门店的既有订单仍要能联系；
 * 门店不存在（含已删）一律 10004（同枚举口径）。
 */
@RestController
@RequestMapping("/api/v1/customer")
public class CustomerChatController {

    /** 与旧 biz_ref 弱引用约定同族：第一版只承认 appointment（后续要加类型走产品评审）。 */
    private static final String BIZ_APPOINTMENT = "appointment";

    private final ChatConversationApi chatConversationApi;
    private final ChatMessageApi chatMessageApi;
    private final StoreQueryApi storeApi;
    private final AppointmentQueryApi appointmentApi;
    private final UserAccountApi userAccountApi;
    private final NotificationQueryApi notificationQueryApi;
    private final ChatMediaSigner mediaSigner;

    public CustomerChatController(ChatConversationApi chatConversationApi, ChatMessageApi chatMessageApi,
                                  StoreQueryApi storeApi, AppointmentQueryApi appointmentApi,
                                  UserAccountApi userAccountApi, NotificationQueryApi notificationQueryApi,
                                  ChatMediaSigner mediaSigner) {
        this.chatConversationApi = chatConversationApi;
        this.chatMessageApi = chatMessageApi;
        this.storeApi = storeApi;
        this.appointmentApi = appointmentApi;
        this.userAccountApi = userAccountApi;
        this.notificationQueryApi = notificationQueryApi;
        this.mediaSigner = mediaSigner;
    }

    @GetMapping("/conversations")
    @PreAuthorize("hasAuthority('perm:chat:view')")
    public Result<PageResult<ConversationView>> conversations(@RequestParam(defaultValue = "1") int pageNum,
                                                              @RequestParam(defaultValue = "20") int pageSize) {
        Long userId = CurrentUserHolder.require().userId();
        PageResult<ConversationRef> page = chatConversationApi.customerConversations(userId, pageNum, pageSize);
        Map<Long, Long> unread = chatConversationApi.customerUnreadByConversation(userId);
        Map<Long, String> storeNames = storeApi.storeNames(
                page.getList().stream().map(ConversationRef::getStoreId).distinct().toList());
        List<ConversationView> list = page.getList().stream()
                .map(c -> toView(c, storeNames.get(c.getStoreId()), unread.getOrDefault(c.getId(), 0L)))
                .toList();
        return Result.ok(PageResult.of(page.getTotal(), page.getPageNum(), page.getPageSize(), list));
    }

    /**
     * 打开（创建或复用）与门店的长期会话。业务关联（bizModule/bizId）只用于：
     * ① 首建时落 origin（此后不变）；② 本次入口回显（entry，不落库）——不会覆盖已有会话的历史来源。
     */
    @PostMapping("/conversations")
    @PreAuthorize("hasAuthority('perm:chat:view')")
    public Result<OpenConversationResponse> open(@Valid @RequestBody OpenConversationCommand command) {
        Long userId = CurrentUserHolder.require().userId();
        if (storeApi.store(command.getStoreId()) == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        String bizModule = normalizeBizModule(command.getBizModule());
        Long bizId = requireValidBiz(userId, command.getStoreId(), bizModule, command.getBizId());

        ConversationRef conv = chatConversationApi.getOrCreate(ConversationCreateCommand.builder()
                .storeId(command.getStoreId())
                .customerUserId(userId)
                .originBizModule(bizModule)
                .originBizId(bizId)
                .build());
        Map<Long, Long> unread = chatConversationApi.customerUnreadByConversation(userId);
        ConversationView view = toView(conv, storeApi.storeNames(List.of(conv.getStoreId())).get(conv.getStoreId()),
                unread.getOrDefault(conv.getId(), 0L));
        return Result.ok(OpenConversationResponse.builder()
                .conversation(view)
                .entryBizModule(bizModule)
                .entryBizId(bizId)
                .build());
    }

    /**
     * 单会话详情（只读，**不清未读**——已读只由 read 接口按明确时机处理）。
     * 归属只认鉴权身份；非本人会话一律 10004（与列表/消息同口径，防枚举）。
     */
    @GetMapping("/conversations/{id}")
    @PreAuthorize("hasAuthority('perm:chat:view')")
    public Result<ConversationView> conversation(@PathVariable String id) {
        Long userId = CurrentUserHolder.require().userId();
        ConversationRef conv = chatConversationApi.conversation(parseId(id), ChatSide.CUSTOMER, userId);
        Map<Long, Long> unread = chatConversationApi.customerUnreadByConversation(userId);
        return Result.ok(toView(conv, storeApi.storeNames(List.of(conv.getStoreId())).get(conv.getStoreId()),
                unread.getOrDefault(conv.getId(), 0L)));
    }

    /**
     * 会话消息（两方向共一口）：beforeId=翻更早（DESC）；afterId=拉更新（ASC，时间序）；
     * 都为空=最近一页。两游标互斥（同传 10001，由服务层统一裁决）。
     */
    @GetMapping("/conversations/{id}/messages")
    @PreAuthorize("hasAuthority('perm:chat:view')")
    public Result<MessageListView> messages(@PathVariable String id,
                                            @RequestParam(required = false) String beforeId,
                                            @RequestParam(required = false) String afterId,
                                            @RequestParam(required = false) Integer limit) {
        Long userId = CurrentUserHolder.require().userId();
        MessagePage page = chatMessageApi.messages(parseId(id), ChatSide.CUSTOMER, userId,
                parseNullableId(beforeId), parseNullableId(afterId), limit);
        return Result.ok(MessageListView.builder()
                .list(page.getList().stream().map(this::toView).toList())
                .hasMore(page.isHasMore())
                .build());
    }

    @PostMapping("/conversations/{id}/messages")
    @PreAuthorize("hasAuthority('perm:chat:send')")
    public Result<MessageView> send(@PathVariable String id, @Valid @RequestBody SendMessageCommand command) {
        Long userId = CurrentUserHolder.require().userId();
        MessageRef ref = chatMessageApi.send(MessageSendCommand.builder()
                .conversationId(parseId(id))
                .senderType(ChatSide.CUSTOMER)
                .senderUserId(userId)
                .senderStoreId(null)
                .senderName(nicknameOf(userId))
                .content(command.getContent())
                .messageType(command.getMessageType())
                .mediaKey(command.getMediaKey())
                .clientMsgId(command.getClientMsgId())
                .bizModule(normalizeBizModule(command.getBizModule()))
                .bizId(sendBizId(command.getBizModule(), command.getBizId()))
                .build());
        return Result.ok(toView(ref));
    }

    @PostMapping("/conversations/{id}/read")
    @PreAuthorize("hasAuthority('perm:chat:view')")
    public Result<Void> read(@PathVariable String id) {
        Long userId = CurrentUserHolder.require().userId();
        chatConversationApi.markRead(parseId(id), ChatSide.CUSTOMER, userId);
        return Result.ok();
    }

    @GetMapping("/conversations/unread-count")
    @PreAuthorize("hasAuthority('perm:chat:view')")
    public Result<Long> unreadCount() {
        return Result.ok(chatConversationApi.customerUnreadTotal(CurrentUserHolder.require().userId()));
    }

    /**
     * 通知未读 × 聊天未读合计（铃铛用）：一次请求返回分账与合计——total 是两源真实加总，
     * 不是两次轮询各自展示造成的重复统计。既有"通知未读"端点语义原样保留（消息中心系统 tab 继续用）。
     */
    @GetMapping("/unread-summary")
    @PreAuthorize("hasAuthority('perm:chat:view') and hasAuthority('perm:ntf:receive')")
    public Result<UnreadSummary> unreadSummary() {
        Long userId = CurrentUserHolder.require().userId();
        long notificationCount = notificationQueryApi.unreadCountForPersonalInbox(userId);
        long chatCount = chatConversationApi.customerUnreadTotal(userId);
        return Result.ok(UnreadSummary.builder()
                .notificationCount(notificationCount)
                .chatCount(chatCount)
                .total(notificationCount + chatCount)
                .build());
    }

    /* ---- 装配与校验 ---- */

    /** 业务关联校验：只认 appointment；单据必须属于当前客户且落在目标门店（否则 10004 防枚举）。 */
    private Long requireValidBiz(Long userId, Long storeId, String bizModule, Long bizId) {
        if (bizModule == null) {
            if (bizId != null) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "业务关联缺 bizModule");
            }
            return null;
        }
        if (!BIZ_APPOINTMENT.equals(bizModule)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "不支持的业务关联类型: " + bizModule);
        }
        if (bizId == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "业务关联缺 bizId");
        }
        AppointmentSelfView apt = appointmentApi.selfDetail(userId, bizId);
        if (apt == null || !storeId.equals(apt.getStoreId())) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        return bizId;
    }

    private String nicknameOf(Long userId) {
        return userAccountApi.profilesByIds(List.of(userId)).stream()
                .map(UserProfileView::getNickname)
                .filter(n -> n != null && !n.isBlank())
                .findFirst().orElse("顾客");
    }

    private static String normalizeBizModule(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static Long sendBizId(String rawModule, Long bizId) {
        return normalizeBizModule(rawModule) == null ? null : bizId;
    }

    /** 雪花 id 字符串化出参、入参也是字符串：非数字一律 10001（不让 NumberFormatException 冒成 10009）。 */
    private static Long parseId(String raw) {
        Long id = parseNullableId(raw);
        if (id == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "非法会话 id");
        }
        return id;
    }

    private static Long parseNullableId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!raw.matches("\\d{1,19}")) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "非法 id");
        }
        return Long.valueOf(raw);
    }

    private static ConversationView toView(ConversationRef c, String storeName, long unread) {
        return ConversationView.builder()
                .id(c.getId()).storeId(c.getStoreId())
                .storeName(storeName == null ? "门店 " + c.getStoreId() : storeName)
                .lastMessagePreview(c.getLastMessagePreview())
                .lastMessageAt(c.getLastMessageAt())
                .unread(unread)
                .originBizModule(c.getOriginBizModule()).originBizId(c.getOriginBizId())
                .build();
    }

    /** 消息视图装配（签名在 ChatGuard 已放行之后：page 来自 message-service 的受守卫查询）。 */
    private MessageView toView(MessageRef m) {
        return MessageView.builder()
                .id(m.getId()).senderType(m.getSenderType()).senderName(m.getSenderNameSnapshot())
                .content(m.getContent()).bizModule(m.getBizModule()).bizId(m.getBizId())
                .messageType(m.getMessageType())
                .mediaUrl(mediaSigner.sign(m.getMediaKey()))
                .createdAt(m.getCreatedAt())
                .build();
    }

    /* ---- 请求体 ---- */

    /** 打开会话：storeId 必填；bizModule/bizId 可空（可空时两者都得空）。 */
    @Data
    public static class OpenConversationCommand {
        @NotNull(message = "storeId必填")
        private Long storeId;
        private String bizModule;
        private Long bizId;
    }

    /** 发送消息：content ≤500（服务端 trim 后非空）；clientMsgId 是客户端首次发送时生成的幂等键，必传。 */
    @Data
    public static class SendMessageCommand {
        @NotBlank(message = "消息内容不能为空")
        @Size(max = 500, message = "消息不超过 500 字")
        private String content;

        /** 空=TEXT（旧客户端兼容）；IMAGE=图片消息（content 固定 [图片] 且 mediaKey 必填） */
        @Size(max = 16, message = "messageType过长")
        private String messageType;

        /** 仅 IMAGE：对象存储 key（u-{uid}/CHAT_IMAGE/…）；TEXT 不得携带 */
        @Size(max = 255, message = "mediaKey过长")
        private String mediaKey;

        @NotBlank(message = "clientMsgId必填")
        @Size(max = 64, message = "clientMsgId过长")
        private String clientMsgId;

        private String bizModule;
        private Long bizId;
    }

    /* ---- 出参（Long 经全局 Jackson 字符串化） ---- */

    @Data
    @Builder
    public static class ConversationView {
        private Long id;
        private Long storeId;
        private String storeName;
        private String lastMessagePreview;
        private LocalDateTime lastMessageAt;
        /** 客户侧未读（对侧发送 AND id > 己方已读游标） */
        private long unread;
        /** 首建来源（弱引用）——≠本次入口 */
        private String originBizModule;
        private Long originBizId;
    }

    /** open 的响应：conversation=权威态；entry=本次入口回显（不落库、不覆盖 origin）。 */
    @Data
    @Builder
    public static class OpenConversationResponse {
        private ConversationView conversation;
        private String entryBizModule;
        private Long entryBizId;
    }

    @Data
    @Builder
    public static class MessageView {
        private Long id;
        /** CUSTOMER/STORE——前端据此区分"自己的消息"（不暴露发送人 id） */
        private String senderType;
        private String senderName;
        private String content;
        /** TEXT/IMAGE——前端据此决定气泡渲染分支（旧行/旧客户端恒 TEXT） */
        private String messageType;
        /** IMAGE=签名后的 GET URL（600s，每次出参重签）；TEXT=null。DB 只存 key，不存 URL */
        private String mediaUrl;
        private String bizModule;
        private Long bizId;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    public static class MessageListView {
        private List<MessageView> list;
        private boolean hasMore;
    }

    @Data
    @Builder
    public static class UnreadSummary {
        private long notificationCount;
        private long chatCount;
        private long total;
    }
}
