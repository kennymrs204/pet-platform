package com.petplatform.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.result.PageResult;
import com.petplatform.notification.api.NotificationQueryApi;
import com.petplatform.notification.api.dto.NotificationView;
import com.petplatform.notification.domain.NotificationEntity;
import com.petplatform.notification.mapper.NotificationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 站内通知读侧（ADR-041）。
 *
 * <p>唯一有判断的部分是 biz_ref 解析：它是弱引用 JSON（{"module":...,"bizId":...}），
 * 通知是不可变历史，写它的时候业务对象存在、读的时候未必，所以<b>解析不出来就当作"不可跳转"</b>，
 * 绝不让一条脏数据显示成整个消息盒的读取失败。
 */
@Slf4j
@Service
public class NotificationQueryService implements NotificationQueryApi {

    private final NotificationMapper notificationMapper;
    private final ObjectMapper json;

    public NotificationQueryService(NotificationMapper notificationMapper, ObjectMapper json) {
        this.notificationMapper = notificationMapper;
        this.json = json;
    }

    /**
     * C 端个人收件箱：固定 {@code store_id = 0}。门店上下文的通知（store_id &gt; 0）
     * 即使收件人是同一个员工账号，也不属于个人消息盒。
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<NotificationView> pageForPersonalInbox(Long userId, int pageNum, int pageSize) {
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        long total = notificationMapper.countForPersonalInbox(userId);
        List<NotificationView> list = total == 0
                ? List.of()
                : notificationMapper.selectForPersonalInbox(userId, size, (long) (page - 1) * size).stream()
                        .map(this::toView)
                        .toList();
        return PageResult.of(total, page, size, list);
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCountForPersonalInbox(Long userId) {
        return notificationMapper.countUnreadForPersonalInbox(userId);
    }

    /**
     * 门店收件箱（ADR-047）。
     *
     * <p>{@code storeId<=0} 直接返回空盒而不是"查全部"：C 端 token 也持有 {@code ntf:receive}，
     * 它打到 {@code /store/notifications} 时 storeCtx 就是 0——此时把过滤条件丢掉，
     * 等于让一个门店 URL 吐出个人消息，两个收件箱的边界就在这一下失效。
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<NotificationView> pageForUserInStore(Long userId, Long storeId, int pageNum, int pageSize) {
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        if (userId == null || storeId == null || storeId <= 0L) {
            return PageResult.of(0L, page, size, List.of());
        }
        long total = notificationMapper.countForUserInStore(userId, storeId);
        List<NotificationView> list = total == 0
                ? List.of()
                : notificationMapper.selectForUserInStore(userId, storeId, size, (long) (page - 1) * size).stream()
                        .map(this::toView)
                        .toList();
        return PageResult.of(total, page, size, list);
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCountInStore(Long userId, Long storeId) {
        if (userId == null || storeId == null || storeId <= 0L) {
            return 0L;
        }
        return notificationMapper.countUnreadInStore(userId, storeId);
    }

    /**
     * 门店消息中心列表的完整未读行数（含 CHAT_MESSAGE）。与 {@link #pageForUserInStore} 一致地
     * 对 {@code storeId<=0} 归 0（C 端 token 打到门店端点时不该看到任何门店行）。
     */
    @Override
    @Transactional(readOnly = true)
    public long unreadCountInStoreForMessageList(Long userId, Long storeId) {
        if (userId == null || storeId == null || storeId <= 0L) {
            return 0L;
        }
        return notificationMapper.countUnreadAllTypesInStore(userId, storeId);
    }

    private NotificationView toView(NotificationEntity row) {
        NotificationView.NotificationViewBuilder builder = NotificationView.builder()
                .id(row.getId())
                .type(row.getType())
                .title(row.getTitle())
                .content(row.getContent())
                .read(row.getReadAt() != null)
                .readAt(row.getReadAt())
                .createdAt(row.getCreatedAt());
        JsonNode ref = parseRef(row);
        if (ref != null) {
            builder.bizModule(ref.path("module").asText(null))
                    .bizId(ref.path("bizId").isNumber() ? ref.path("bizId").asLong() : null)
                    // H5：chat 提醒的深链定位键（非 chat 的 JSON 里没有这个键 → null）
                    .conversationId(ref.path("conversationId").isNumber() ? ref.path("conversationId").asLong() : null);
        }
        return builder.build();
    }

    /** 解析失败只丢跳转信息，不影响这条消息本身可读。 */
    private JsonNode parseRef(NotificationEntity row) {
        String raw = row.getBizRef();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(raw);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            log.warn("通知 biz_ref 解析失败，按不可跳转处理 id={}: {}", row.getId(), e.getMessage());
            return null;
        }
    }
}
