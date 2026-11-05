package com.petplatform.app.web;

import com.petplatform.app.service.ChatMediaSigner;
import com.petplatform.app.service.StoreCustomerQueryService;
import com.petplatform.appointment.api.AppointmentQueryApi;
import com.petplatform.appointment.api.dto.AppointmentSelfView;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.employee.api.dto.EmployeeBrief;
import com.petplatform.message.api.ChatConversationApi;
import com.petplatform.message.api.ChatMessageApi;
import com.petplatform.message.api.ChatSide;
import com.petplatform.message.api.dto.ConversationCreateCommand;
import com.petplatform.message.api.dto.ConversationRef;
import com.petplatform.message.api.dto.MessagePage;
import com.petplatform.message.api.dto.MessageRef;
import com.petplatform.message.api.dto.MessageSendCommand;
import com.petplatform.notification.api.NotificationApi;
import com.petplatform.notification.api.NotificationQueryApi;
import com.petplatform.user.api.UserAccountApi;
import com.petplatform.user.api.dto.UserProfileView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * B 端客户沟通（会话/消息/未读 + 通知×聊天合计未读）。
 *
 * <p>门店范围只认已校验的 X-Store-Id 上下文（入参不含 storeId）；会话归属复核在
 * message-service 的 ChatGuard（store_id = 上下文，防跨店）；"该客户是否本店客户"复用
 * {@code requireCustomer}（预约∪建档口径，10004 防枚举）。发送人姓名按**鉴权身份**解析
 * （员工档案名 → 账号昵称 → "门店"），不接受前端传入的 senderName。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/store")
public class StoreChatController {

    private static final String BIZ_APPOINTMENT = "appointment";

    /** keyword 上限（H1：与客户列表"姓名/手机"同族的合理上限——姓名≤50、号≤11，50 足够容纳片段）。 */
    private static final int MAX_KEYWORD_LEN = 50;
    /** 搜索态页大小上限——与 message 侧 MAX_PAGE_SIZE 同口径（切片分页在过滤后由本层完成）。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final ChatConversationApi chatConversationApi;
    private final ChatMessageApi chatMessageApi;
    private final StoreCustomerQueryService customerQueryService;
    private final UserAccountApi userAccountApi;
    private final EmployeeQueryApi employeeApi;
    private final AppointmentQueryApi appointmentApi;
    private final NotificationQueryApi notificationQueryApi;
    private final NotificationApi notificationApi;
    private final ChatMediaSigner mediaSigner;

    public StoreChatController(ChatConversationApi chatConversationApi, ChatMessageApi chatMessageApi,
                               StoreCustomerQueryService customerQueryService, UserAccountApi userAccountApi,
                               EmployeeQueryApi employeeApi, AppointmentQueryApi appointmentApi,
                               NotificationQueryApi notificationQueryApi, NotificationApi notificationApi,
                               ChatMediaSigner mediaSigner) {
        this.chatConversationApi = chatConversationApi;
        this.chatMessageApi = chatMessageApi;
        this.customerQueryService = customerQueryService;
        this.userAccountApi = userAccountApi;
        this.employeeApi = employeeApi;
        this.appointmentApi = appointmentApi;
        this.notificationQueryApi = notificationQueryApi;
        this.notificationApi = notificationApi;
        this.mediaSigner = mediaSigner;
    }

    /**
     * 本店会话列表（H1 服务端搜索）：
     * · 无 keyword 且无 unreadOnly → 原分页路径（countStore+selectStorePage，零行为变化）；
     * · 有任一过滤条件 → 全量 refs → 批量 profile → keyword 过滤（姓名∥完整号∥脱敏号）
     *   → unreadOnly 过滤 → **过滤后**再分页 → view 装配（绝不"先分页再过滤"）。
     * keyword 只是过滤条件：门店范围仍由 X-Store-Id 上下文 + store_id 查询条件唯一决定。
     */
    @GetMapping("/conversations")
    @PreAuthorize("hasAuthority('perm:store:chat:view')")
    public Result<PageResult<StoreConversationView>> conversations(@RequestParam(defaultValue = "1") int pageNum,
                                                                   @RequestParam(defaultValue = "20") int pageSize,
                                                                   @RequestParam(required = false) String keyword,
                                                                   @RequestParam(defaultValue = "false") boolean unreadOnly) {
        Long storeId = requireStoreCtx(CurrentUserHolder.require());
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        if (kw.length() > MAX_KEYWORD_LEN) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "搜索关键字过长（≤" + MAX_KEYWORD_LEN + " 字）");
        }
        if (kw.isEmpty() && !unreadOnly) {
            return Result.ok(pageOf(storeId, pageNum, pageSize));
        }
        return Result.ok(filteredPageOf(storeId, pageNum, pageSize, kw, unreadOnly));
    }

    /** 原分页路径（无过滤条件时零变化）：countStore + selectStorePage + 分页内 profile 装配。 */
    private PageResult<StoreConversationView> pageOf(Long storeId, int pageNum, int pageSize) {
        PageResult<ConversationRef> page = chatConversationApi.storeConversations(storeId, pageNum, pageSize);
        Map<Long, Long> unread = chatConversationApi.storeUnreadByConversation(storeId);
        Map<Long, UserProfileView> profiles = profilesOf(page.getList().stream()
                .map(ConversationRef::getCustomerUserId).distinct().toList());
        List<StoreConversationView> list = page.getList().stream()
                .map(c -> toView(c, profiles.get(c.getCustomerUserId()), unread.getOrDefault(c.getId(), 0L)))
                .toList();
        return PageResult.of(page.getTotal(), page.getPageNum(), page.getPageSize(), list);
    }

    /**
     * H1 搜索态：全量 refs →（有关键字时）一次批量 profile → keyword 过滤 → unreadOnly 过滤
     * → **过滤完成后**切片分页 → view。仅 unreadOnly 时无需 profile 参与过滤，装配随页批量。
     */
    private PageResult<StoreConversationView> filteredPageOf(Long storeId, int pageNum, int pageSize,
                                                             String kw, boolean unreadOnly) {
        List<ConversationRef> refs = chatConversationApi.allStoreConversations(storeId);
        Map<Long, Long> unread = chatConversationApi.storeUnreadByConversation(storeId);
        Map<Long, UserProfileView> profiles = kw.isEmpty() ? null
                : profilesOf(refs.stream().map(ConversationRef::getCustomerUserId).distinct().toList());
        List<ConversationRef> matched = refs.stream()
                .filter(c -> kw.isEmpty() || keywordMatches(c, profiles.get(c.getCustomerUserId()), kw))
                .filter(c -> !unreadOnly || unread.getOrDefault(c.getId(), 0L) > 0)
                .toList();
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int from = Math.min((page - 1) * size, matched.size());
        int to = Math.min(from + size, matched.size());
        List<ConversationRef> slice = matched.subList(from, to);
        Map<Long, UserProfileView> pageProfiles = profiles != null ? profiles
                : profilesOf(slice.stream().map(ConversationRef::getCustomerUserId).distinct().toList());
        List<StoreConversationView> list = slice.stream()
                .map(c -> toView(c, pageProfiles.get(c.getCustomerUserId()), unread.getOrDefault(c.getId(), 0L)))
                .toList();
        return PageResult.of(matched.size(), page, size, list);
    }

    /**
     * H1 关键词谓词（与客户列表同族的"姓名/手机"子集，**不含宠物名**）：
     * 客户姓名（含"顾客{id}"兜底名，与出参同字）∥ 完整手机号 ∥ 脱敏手机号；均已折叠大小写。
     */
    private static boolean keywordMatches(ConversationRef c, UserProfileView profile, String kw) {
        String nickname = profile == null ? null : profile.getNickname();
        String name = nickname == null || nickname.isBlank() ? "顾客" + c.getCustomerUserId() : nickname;
        if (name.toLowerCase().contains(kw)) {
            return true;
        }
        if (profile == null || profile.getPhone() == null) {
            return false;
        }
        String phone = profile.getPhone().toLowerCase();
        return phone.contains(kw) || StoreCustomerQueryService.mask(profile.getPhone()).toLowerCase().contains(kw);
    }

    /**
     * 打开（创建或复用）与该客户的长期会话。客户必须在本店客户集（预约∪建档）——非本店客户 10004；
     * 业务关联（可空）须属于该客户且落在本店。
     */
    @PostMapping("/conversations")
    @PreAuthorize("hasAuthority('perm:store:chat:view')")
    public Result<OpenConversationResponse> open(@Valid @RequestBody OpenConversationCommand command) {
        Long storeId = requireStoreCtx(CurrentUserHolder.require());
        customerQueryService.requireCustomer(storeId, command.getCustomerUserId());
        String bizModule = normalizeBizModule(command.getBizModule());
        Long bizId = requireValidBiz(command.getCustomerUserId(), storeId, bizModule, command.getBizId());

        ConversationRef conv = chatConversationApi.getOrCreate(ConversationCreateCommand.builder()
                .storeId(storeId)
                .customerUserId(command.getCustomerUserId())
                .originBizModule(bizModule)
                .originBizId(bizId)
                .build());
        Map<Long, UserProfileView> profiles = profilesOf(List.of(conv.getCustomerUserId()));
        Map<Long, Long> unread = chatConversationApi.storeUnreadByConversation(storeId);
        return Result.ok(OpenConversationResponse.builder()
                .conversation(toView(conv, profiles.get(conv.getCustomerUserId()), unread.getOrDefault(conv.getId(), 0L)))
                .entryBizModule(bizModule)
                .entryBizId(bizId)
                .build());
    }

    /**
     * 单会话详情（只读，**不清未读**——已读只由 read 接口按明确时机处理）。
     * 归属只认 X-Store-Id 上下文；非本店会话一律 10004（与列表/消息同口径，防枚举）。
     */
    @GetMapping("/conversations/{id}")
    @PreAuthorize("hasAuthority('perm:store:chat:view')")
    public Result<StoreConversationView> conversation(@PathVariable String id) {
        Long storeId = requireStoreCtx(CurrentUserHolder.require());
        ConversationRef conv = chatConversationApi.conversation(parseId(id), ChatSide.STORE, storeId);
        Map<Long, UserProfileView> profiles = profilesOf(List.of(conv.getCustomerUserId()));
        Map<Long, Long> unread = chatConversationApi.storeUnreadByConversation(storeId);
        return Result.ok(toView(conv, profiles.get(conv.getCustomerUserId()), unread.getOrDefault(conv.getId(), 0L)));
    }

    /**
     * 会话消息（两方向共一口）：beforeId=翻更早（DESC）；afterId=拉更新（ASC，时间序）；
     * 都为空=最近一页。两游标互斥（同传 10001，由服务层统一裁决）。
     */
    @GetMapping("/conversations/{id}/messages")
    @PreAuthorize("hasAuthority('perm:store:chat:view')")
    public Result<MessageListView> messages(@PathVariable String id,
                                            @RequestParam(required = false) String beforeId,
                                            @RequestParam(required = false) String afterId,
                                            @RequestParam(required = false) Integer limit) {
        Long storeId = requireStoreCtx(CurrentUserHolder.require());
        MessagePage page = chatMessageApi.messages(parseId(id), ChatSide.STORE, storeId,
                parseNullableId(beforeId), parseNullableId(afterId), limit);
        return Result.ok(MessageListView.builder()
                .list(page.getList().stream().map(this::toView).toList())
                .hasMore(page.isHasMore())
                .build());
    }

    @PostMapping("/conversations/{id}/messages")
    @PreAuthorize("hasAuthority('perm:store:chat:send')")
    public Result<MessageView> send(@PathVariable String id, @Valid @RequestBody SendMessageCommand command) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        MessageRef ref = chatMessageApi.send(MessageSendCommand.builder()
                .conversationId(parseId(id))
                .senderType(ChatSide.STORE)
                .senderUserId(principal.userId())
                .senderStoreId(storeId)
                .senderName(resolveSenderName(principal.userId(), storeId))
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
    @PreAuthorize("hasAuthority('perm:store:chat:view')")
    public Result<Void> read(@PathVariable String id) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        Long conversationId = parseId(id);
        chatConversationApi.markRead(conversationId, ChatSide.STORE, storeId);
        // H5 旁路：读会话顺带清本人在此会话的 CHAT_MESSAGE 提醒（Package 1 联动）。
        // 主链优先——提醒标记失败只记 warn，绝不影响聊天已读游标（旁路吞异常，同通知一贯纪律）。
        try {
            notificationApi.markChatRemindersRead(principal.userId(), storeId, conversationId);
        } catch (RuntimeException e) {
            log.warn("读会话顺带清理聊天提醒失败 conversationId={} storeId={}: {}",
                    conversationId, storeId, e.getMessage());
        }
        return Result.ok();
    }

    @GetMapping("/conversations/unread-count")
    @PreAuthorize("hasAuthority('perm:store:chat:view')")
    public Result<Long> unreadCount() {
        return Result.ok(chatConversationApi.storeUnreadTotal(requireStoreCtx(CurrentUserHolder.require())));
    }

    /**
     * 本店上下文的通知未读 × 聊天未读合计（铃铛用）；既有通知未读端点语义原样保留。
     *
     * <p>{@code listUnreadCount} 专供 B 端<b>消息中心</b>：含 {@code CHAT_MESSAGE} 提醒行，与该列表实际
     * 渲染的行、以及 {@code markAllReadInStore} 的更新范围一致。它<b>刻意区别于</b> {@code notificationCount}
     * （铃铛分账，排除 chat 以免与 chatCount 双计）——消息中心不能拿铃铛那个数当"列表还有多少未读"。
     */
    @GetMapping("/unread-summary")
    @PreAuthorize("hasAuthority('perm:store:chat:view') and hasAuthority('perm:ntf:receive')")
    public Result<UnreadSummary> unreadSummary() {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        long notificationCount = notificationQueryApi.unreadCountInStore(principal.userId(), storeId);
        long chatCount = chatConversationApi.storeUnreadTotal(storeId);
        return Result.ok(UnreadSummary.builder()
                .notificationCount(notificationCount)
                .chatCount(chatCount)
                .total(notificationCount + chatCount)
                .listUnreadCount(notificationQueryApi.unreadCountInStoreForMessageList(principal.userId(), storeId))
                .build());
    }

    /* ---- 装配与校验 ---- */

    /**
     * 发送人姓名（服务端解析，不接受前端传入）：员工档案名 → 账号昵称 → "门店"。
     * 三段回退保底"不伪造、不空名"——员工改档/离职后历史消息另有快照兜底（消息行存快照）。
     */
    private String resolveSenderName(Long userId, Long storeId) {
        Long employeeId = employeeApi.employeeIdInStore(userId, storeId);
        if (employeeId != null) {
            String name = employeeApi.briefsInStore(List.of(employeeId), storeId).stream()
                    .map(EmployeeBrief::getName)
                    .filter(n -> n != null && !n.isBlank())
                    .findFirst().orElse(null);
            if (name != null) {
                return name;
            }
        }
        String nickname = userAccountApi.profilesByIds(List.of(userId)).stream()
                .map(UserProfileView::getNickname)
                .filter(n -> n != null && !n.isBlank())
                .findFirst().orElse(null);
        return nickname == null ? "门店" : nickname;
    }

    /** 业务关联校验：只认 appointment；单据必须属于该客户且落在本店（否则 10004 防枚举）。 */
    private Long requireValidBiz(Long customerUserId, Long storeId, String bizModule, Long bizId) {
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
        AppointmentSelfView apt = appointmentApi.selfDetail(customerUserId, bizId);
        if (apt == null || !storeId.equals(apt.getStoreId())) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        return bizId;
    }

    private Map<Long, UserProfileView> profilesOf(List<Long> userIds) {
        Map<Long, UserProfileView> map = new HashMap<>();
        if (!userIds.isEmpty()) {
            userAccountApi.profilesByIds(userIds).forEach(p -> map.put(p.getUserId(), p));
        }
        return map;
    }

    private static String normalizeBizModule(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static Long sendBizId(String rawModule, Long bizId) {
        return normalizeBizModule(rawModule) == null ? null : bizId;
    }

    private Long requireStoreCtx(AuthPrincipal principal) {
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }

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

    private static StoreConversationView toView(ConversationRef c, UserProfileView profile, long unread) {
        String nickname = profile == null ? null : profile.getNickname();
        return StoreConversationView.builder()
                .id(c.getId()).customerUserId(c.getCustomerUserId())
                .customerName(nickname == null || nickname.isBlank() ? "顾客" + c.getCustomerUserId() : nickname)
                .phoneMasked(profile == null ? "" : StoreCustomerQueryService.mask(profile.getPhone()))
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

    @Data
    public static class OpenConversationCommand {
        @NotNull(message = "customerUserId必填")
        private Long customerUserId;
        private String bizModule;
        private Long bizId;
    }

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

    /* ---- 出参 ---- */

    @Data
    @Builder
    public static class StoreConversationView {
        private Long id;
        private Long customerUserId;
        private String customerName;
        /** 前 3 后 4 脱敏（服务端出口唯一形态） */
        private String phoneMasked;
        private String lastMessagePreview;
        private LocalDateTime lastMessageAt;
        /** 门店侧未读（客户发送 AND id > 门店已读游标；门店级共享已读） */
        private long unread;
        private String originBizModule;
        private Long originBizId;
    }

    @Data
    @Builder
    public static class OpenConversationResponse {
        private StoreConversationView conversation;
        private String entryBizModule;
        private Long entryBizId;
    }

    @Data
    @Builder
    public static class MessageView {
        private Long id;
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
        /** 消息中心列表完整未读（含 CHAT_MESSAGE），与 markAllReadInStore 更新范围一致；不用于铃铛。 */
        private long listUnreadCount;
    }
}
