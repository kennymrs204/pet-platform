package com.petplatform.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.petplatform.notification.api.NotificationApi;
import com.petplatform.notification.api.dto.NotificationPush;
import com.petplatform.notification.domain.NotificationEntity;
import com.petplatform.notification.mapper.NotificationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 站内通知落库实现（ADR-037 §5）。<b>只入库，不投递</b>——没有推送通道，也没有未读/已读端点，
 * 所以这些行当前在界面上看不到。之所以仍要写：把"该给谁发、发什么、在哪个时点发"固化成代码与审计行，
 * notification 段落地时只补投递与查询，不必回头改退款/订单链。
 *
 * <p>失败策略：通知是旁路，不能把主链拖下水——序列化或入库异常一律降级为 warn 日志后吞掉。
 * 注意本方法多在 {@code @EventListener} 的同事务里被调用，抛异常会回滚整笔业务写，
 * 所以这里必须自己兜住。
 */
@Slf4j
@Service
public class NotificationService implements NotificationApi {

    /** H5 聊天提醒的 type/module（与 app 侧监听器同一约定）。 */
    private static final String CHAT_TYPE = "CHAT_MESSAGE";
    private static final String CHAT_MODULE = "chat";
    /** MySQL 命名锁前缀；锁名按会话隔离，跨应用实例共享同一个 MySQL 实例。 */
    private static final String CHAT_LOCK_PREFIX = "ntf:chat:";
    private static final int CHAT_LOCK_TIMEOUT_SECONDS = 5;

    private final NotificationMapper notificationMapper;
    private final ObjectMapper json;
    private final JdbcTemplate jdbcTemplate;

    /** 单测兼容构造：无 JdbcTemplate 时 chat 提醒按 fail-closed 丢弃（不绕过并发保护）。 */
    public NotificationService(NotificationMapper notificationMapper, ObjectMapper json) {
        this(notificationMapper, json, null);
    }

    @Autowired
    public NotificationService(NotificationMapper notificationMapper, ObjectMapper json, JdbcTemplate jdbcTemplate) {
        this.notificationMapper = notificationMapper;
        this.json = json;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void push(NotificationPush push) {
        if (invalid(push)) {
            return;
        }
        try {
            notificationMapper.insert(toRow(push));
        } catch (Exception e) {
            log.warn("站内通知落库失败，降级丢弃 userId={} type={}: {}", push.userId(), push.type(), e.getMessage());
        }
    }

    /**
     * 幂等落库（ADR-045）：条件 INSERT，同语义已存在则一行不写。
     * 去重键是 {@code (userId, storeId, type, bizModule, bizId)}——所以<b>没有业务引用的推送无法去重</b>，
     * 那时退化成普通插入（宁可多一条，也不静默丢一条真消息）。
     *
     * <p>H5 聊天提醒额外走 {@link #pushChatOnce}：同一会话的消息事务可能逆序提交，两个监听器会看到
     * 不同的"最小未读消息"，仅靠 bizId 去重会各写一条。chat 分支用 MySQL 命名锁把
     * "查未读提醒 → 规范化 bizId → 写入"串行化，锁在事务完成后才释放（跨实例安全）。
     */
    @Override
    @Transactional
    public boolean pushOnce(NotificationPush push) {
        if (invalid(push)) {
            return false;
        }
        if (push.bizModule() == null || push.bizId() == null) {
            push(push);
            return true;
        }
        try {
            if (isChatReminder(push)) {
                return pushChatOnce(push);
            }
            return insertOnce(push);
        } catch (Exception e) {
            log.warn("站内通知幂等落库失败，降级丢弃 userId={} type={}: {}", push.userId(), push.type(), e.getMessage());
            return false;
        }
    }

    private boolean insertOnce(NotificationPush push) throws Exception {
        NotificationEntity row = toRow(push);
        if (row.getId() == null) {
            row.setId(IdWorker.getId());     // 裸 SQL 不走 MyBatis-Plus 的 ASSIGN_ID，需自带主键
        }
        boolean written = notificationMapper.insertIfAbsent(row, push.bizModule(),
                String.valueOf(push.bizId())) > 0;
        if (!written) {
            log.debug("同语义通知已存在，幂等丢弃 userId={} type={} biz={}:{}", push.userId(), push.type(),
                    push.bizModule(), push.bizId());
        }
        return written;
    }

    private static boolean isChatReminder(NotificationPush push) {
        return CHAT_TYPE.equals(push.type()) && CHAT_MODULE.equals(push.bizModule())
                && push.conversationId() != null;
    }

    /**
     * H5：聊天提醒的并发安全落库。
     *
     * <p><b>为什么需要锁</b>：同一会话的 A/B 两条消息若 B 先提交、A 后提交，两个 AFTER_COMMIT
     * 监听器会分别看到 minUnread=B 与 minUnread=A，各自 pushOnce 会写出 bizId=B / bizId=A 两条提醒。
     * pushOnce 的去重键含 bizId，无法合并这两个不同的头。
     *
     * <p><b>锁协议</b>：按 conversationId 取 MySQL 命名锁（server-wide，多个应用实例共享）；
     * 锁内只做一件事——若该用户/门店/会话已有未读 CHAT_MESSAGE，则把它的 bizId 规范化为
     * {@code min(已有 bizId, 本次 bizId)}（真正的未读段首条），并把多余未读行标已读；否则插入。
     * 锁在<b>事务提交/回滚之后</b>释放（TransactionSynchronization），保证下一个实例读到已提交结果。
     *
     * <p>取锁失败/超时按 fail-closed 丢弃本条提醒（warn）：宁可少一条旁路提醒，也不绕过锁制造重复。
     */
    private boolean pushChatOnce(NotificationPush push) {
        if (jdbcTemplate == null) {
            log.warn("聊天提醒未落库：JdbcTemplate 不可用，无法执行并发保护 conversationId={}",
                    push.conversationId());
            return false;
        }
        String lockName = CHAT_LOCK_PREFIX + push.conversationId();
        Integer acquired;
        try {
            acquired = jdbcTemplate.queryForObject("SELECT GET_LOCK(?, ?)",
                    new Object[]{lockName, CHAT_LOCK_TIMEOUT_SECONDS}, Integer.class);
        } catch (Exception e) {
            log.warn("聊天提醒取锁失败，丢弃本条提醒 conversationId={}: {}", push.conversationId(), e.getMessage());
            return false;
        }
        if (acquired == null || acquired != 1) {
            log.warn("聊天提醒取锁超时，丢弃本条提醒 conversationId={} lock={}", push.conversationId(), lockName);
            return false;
        }

        boolean releaseAfterTransaction = false;
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()
                    && TransactionSynchronizationManager.isActualTransactionActive()) {
                // 锁必须在事务提交/回滚之后释放：提前释放会让另一个实例在提交前读到"没有未读行"
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        releaseChatLock(lockName);
                    }
                });
                releaseAfterTransaction = true;
            }
            return pushChatOnceLocked(push);
        } finally {
            if (!releaseAfterTransaction) {
                releaseChatLock(lockName);
            }
        }
    }

    private boolean pushChatOnceLocked(NotificationPush push) {
        List<NotificationEntity> unread = notificationMapper.selectList(new QueryWrapper<NotificationEntity>()
                .eq("user_id", push.userId())
                .eq("store_id", push.storeIdOrZero())
                .eq("type", push.type())
                .isNull("read_at"));
        List<NotificationEntity> sameConversation = unread.stream()
                .filter(row -> isSameChatConversation(row, push.conversationId()))
                .toList();
        if (!sameConversation.isEmpty()) {
            canonicalizeChatReminder(push, sameConversation);
            return false;
        }
        try {
            return insertOnce(push);
        } catch (Exception e) {
            log.warn("聊天提醒落库失败，降级丢弃 userId={} conversationId={}: {}",
                    push.userId(), push.conversationId(), e.getMessage());
            return false;
        }
    }

    /**
     * 同一未读段已有提醒时，不新增行：保留最小 bizId 的那条并把 bizId 纠正为真正的段首
     * （A/B 逆序提交时，B 先写、后到的 A 更小），其余未读行标已读。
     */
    private void canonicalizeChatReminder(NotificationPush push, List<NotificationEntity> rows) {
        NotificationEntity keep = null;
        Long keepBizId = null;
        long canonicalBizId = push.bizId();
        for (NotificationEntity row : rows) {
            Long bizId = chatBizId(row);
            if (bizId != null && bizId < canonicalBizId) {
                canonicalBizId = bizId;
            }
            if (keep == null || (bizId != null && (keepBizId == null || bizId < keepBizId))) {
                keep = row;
                keepBizId = bizId;
            }
        }
        if (keep == null) {
            return;
        }
        if (keepBizId == null || keepBizId != canonicalBizId) {
            try {
                keep.setBizRef(bizRefJson(CHAT_MODULE, canonicalBizId, push.conversationId()));
                notificationMapper.updateById(keep);
                log.debug("聊天提醒合并：conversationId={} bizId {} → {}", push.conversationId(),
                        keepBizId, canonicalBizId);
            } catch (Exception e) {
                log.warn("聊天提醒合并失败（保留旧 bizId） conversationId={}: {}",
                        push.conversationId(), e.getMessage());
            }
        }
        for (NotificationEntity row : rows) {
            if (row.getId() == null || row.getId().equals(keep.getId())) {
                continue;
            }
            try {
                notificationMapper.updateMarkReadInStore(
                        push.userId(), push.storeIdOrZero(), row.getId());
            } catch (Exception e) {
                log.warn("聊天提醒去重标记失败 id={} conversationId={}: {}",
                        row.getId(), push.conversationId(), e.getMessage());
            }
        }
    }

    private boolean isSameChatConversation(NotificationEntity row, Long conversationId) {
        JsonNode ref = parseChatRef(row);
        return ref != null && ref.path("conversationId").isNumber()
                && ref.path("conversationId").asLong() == conversationId;
    }

    private Long chatBizId(NotificationEntity row) {
        JsonNode ref = parseChatRef(row);
        return ref != null && ref.path("bizId").isNumber() ? ref.path("bizId").asLong() : null;
    }

    private JsonNode parseChatRef(NotificationEntity row) {
        if (row.getBizRef() == null || row.getBizRef().isBlank()) {
            return null;
        }
        try {
            JsonNode ref = json.readTree(row.getBizRef());
            return ref != null && CHAT_MODULE.equals(ref.path("module").asText()) ? ref : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void releaseChatLock(String lockName) {
        try {
            Integer released = jdbcTemplate.queryForObject("SELECT RELEASE_LOCK(?)",
                    new Object[]{lockName}, Integer.class);
            if (released == null || released != 1) {
                log.warn("聊天提醒锁释放未确认 lock={} result={}", lockName, released);
            }
        } catch (Exception e) {
            log.warn("聊天提醒锁释放失败 lock={}: {}", lockName, e.getMessage());
        }
    }

    private static boolean invalid(NotificationPush push) {
        return push == null || push.userId() == null || push.type() == null;
    }

    /**
     * H5：读会话联动清提醒——把该收件人在该门店下、biz_ref 指向该会话的未读 CHAT_MESSAGE
     * 全部标已读（幂等；返回本次实际变更条数）。
     *
     * <p>只清本人（门店已读游标是门店级共享的，但提醒行按收件人各自持有）：谁读会话清谁的提醒；
     * 旁路语义——异常降级 warn 吞掉（调用方还会再兜一层），绝不影响聊天 read 主链。
     */
    @Override
    public int markChatRemindersRead(Long userId, Long storeId, Long conversationId) {
        if (userId == null || storeId == null || storeId <= 0L || conversationId == null) {
            return 0;
        }
        try {
            return notificationMapper.markChatRemindersRead(userId, storeId, conversationId);
        } catch (Exception e) {
            log.warn("聊天提醒联动已读失败 userId={} storeId={} conversationId={}: {}",
                    userId, storeId, conversationId, e.getMessage());
            return 0;
        }
    }

    private NotificationEntity toRow(NotificationPush push) throws Exception {
        NotificationEntity row = new NotificationEntity();
        row.setUserId(push.userId());
        // store_id 是 NOT NULL 列，而裸 SQL 会把 Java 的 null 直译成 SQL NULL（MyBatis-Plus 的 insert
        // 则干脆不带这列，两条路对"缺省"的处理并不一致）——所以在唯一入口归一成 0，不让它靠列默认值碰运气。
        row.setStoreId(push.storeIdOrZero());
        row.setType(push.type());
        row.setTitle(push.title());
        row.setContent(push.content());
        if (push.bizModule() != null) {
            row.setBizRef(bizRefJson(push.bizModule(), push.bizId(), push.conversationId()));
        }
        return row;
    }

    /** biz_ref 的唯一构造点：非 chat 类型 conversationId 为 null，JSON 形状与既往完全一致。 */
    private String bizRefJson(String module, Long bizId, Long conversationId) throws Exception {
        ObjectNode ref = json.createObjectNode();
        ref.put("module", module);
        if (bizId != null) {
            ref.put("bizId", bizId);
        }
        if (conversationId != null) {
            ref.put("conversationId", conversationId);
        }
        return json.writeValueAsString(ref);
    }
}
