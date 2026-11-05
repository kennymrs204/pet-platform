package com.petplatform.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.message.domain.MessageEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 消息读写（msg_message，表 Owner=message，append-only）。
 *
 * <p>未读统计的两层结构：会话行未读（列表装配）与全局汇总（角标）都用
 * "对侧发送 AND id &gt; 己方已读游标"的 COUNT——没有第二个未读事实源。
 */
@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {

    /**
     * 幂等插入（快路径，照 ADR-045 的 insertIfAbsent 模式）：同 (conversation_id, client_msg_id)
     * 已有则一行不写，返回 0。**并发兜底在表级 uk_conv_client 唯一约束**——两个并发请求同时通过
     * NOT EXISTS 时，后到者触发唯一键冲突，由服务层捕获后回查原消息（不在此处吞错）。
     *
     * @return 1=本次真插入；0=幂等命中（已有同 clientMsgId）
     */
    @Insert("INSERT INTO msg_message (id, conversation_id, sender_type, sender_user_id, sender_name_snapshot,"
            + " content, message_type, media_key, media_content_type, media_size,"
            + " biz_module, biz_id, client_msg_id, created_at)"
            + " SELECT #{e.id}, #{e.conversationId}, #{e.senderType}, #{e.senderUserId}, #{e.senderNameSnapshot},"
            + " #{e.content}, #{e.messageType}, #{e.mediaKey}, #{e.mediaContentType}, #{e.mediaSize},"
            + " #{e.bizModule}, #{e.bizId}, #{e.clientMsgId}, #{e.createdAt} FROM DUAL"
            + " WHERE NOT EXISTS (SELECT 1 FROM msg_message"
            + " WHERE conversation_id = #{e.conversationId} AND client_msg_id = #{e.clientMsgId})")
    int insertIfAbsent(@Param("e") MessageEntity entity);

    /** 幂等回查：拿到"原消息"（重复提交的返回体）/ 并发冲突兜底后的取回。 */
    @Select("SELECT * FROM msg_message WHERE conversation_id = #{conversationId} AND client_msg_id = #{clientMsgId}")
    MessageEntity selectByClientMsgId(@Param("conversationId") Long conversationId,
                                      @Param("clientMsgId") String clientMsgId);

    /**
     * 会话内消息（游标，两方向共一口）：
     * beforeId=翻更早（id &lt; beforeId，**DESC**）；afterId=拉更新（id &gt; afterId，**ASC**，时间序）；
     * 都为空=最近一页（DESC）。两游标由服务层保证互斥。
     * hasMore 用"多取一条"在服务层判（本口只按 limit 取）。排序方向用 &lt;choose&gt; 输出两个
     * 常量 SQL 文本（不拼接参数——ORDER BY 方向不可参数化）。
     * 动态条件必须 &lt;script&gt; 包裹（本仓踩过"漏 script 单测全绿、活体才现形"的坑）。
     */
    @Select("""
            <script>
            SELECT * FROM msg_message
            WHERE conversation_id = #{conversationId}
            <if test="beforeId != null"> AND id &lt; #{beforeId}</if>
            <if test="afterId != null"> AND id &gt; #{afterId}</if>
            <choose>
              <when test="afterId != null">ORDER BY id ASC</when>
              <otherwise>ORDER BY id DESC</otherwise>
            </choose>
            LIMIT #{limit}
            </script>
            """)
    List<MessageEntity> selectPage(@Param("conversationId") Long conversationId,
                                   @Param("beforeId") Long beforeId, @Param("afterId") Long afterId,
                                   @Param("limit") int limit);

    /** 已读快照用：当刻会话最大消息 id（无消息=0）。 */
    @Select("SELECT COALESCE(MAX(id), 0) FROM msg_message WHERE conversation_id = #{conversationId}")
    long selectMaxId(@Param("conversationId") Long conversationId);

    /* ---- 未读统计（会话行 / 汇总；两套口径各自成对，不跨用） ---- */

    @Select("SELECT m.conversation_id AS conversationId, COUNT(*) AS unread"
            + " FROM msg_message m JOIN msg_conversation c ON c.id = m.conversation_id"
            + " WHERE c.customer_user_id = #{customerUserId} AND m.sender_type = 'STORE'"
            + " AND m.id > COALESCE(c.customer_last_read_message_id, 0)"
            + " GROUP BY m.conversation_id")
    List<UnreadRow> customerUnreadByConversation(@Param("customerUserId") Long customerUserId);

    @Select("SELECT COUNT(*) FROM msg_message m JOIN msg_conversation c ON c.id = m.conversation_id"
            + " WHERE c.customer_user_id = #{customerUserId} AND m.sender_type = 'STORE'"
            + " AND m.id > COALESCE(c.customer_last_read_message_id, 0)")
    long customerUnreadTotal(@Param("customerUserId") Long customerUserId);

    @Select("SELECT m.conversation_id AS conversationId, COUNT(*) AS unread"
            + " FROM msg_message m JOIN msg_conversation c ON c.id = m.conversation_id"
            + " WHERE c.store_id = #{storeId} AND m.sender_type = 'CUSTOMER'"
            + " AND m.id > COALESCE(c.store_last_read_message_id, 0)"
            + " GROUP BY m.conversation_id")
    List<UnreadRow> storeUnreadByConversation(@Param("storeId") Long storeId);

    @Select("SELECT COUNT(*) FROM msg_message m JOIN msg_conversation c ON c.id = m.conversation_id"
            + " WHERE c.store_id = #{storeId} AND m.sender_type = 'CUSTOMER'"
            + " AND m.id > COALESCE(c.store_last_read_message_id, 0)")
    long storeUnreadTotal(@Param("storeId") Long storeId);

    /**
     * H5（T1）：门店视角该会话**最小未读客户消息 id**（无未读→null）。
     * 判据与 storeUnread* 同口径（对侧=客户发送 ∧ id &gt; 门店已读游标）——
     * "当前未读段的首条"由此唯一确定，供提醒去重（而不是拿 lastMessageId 猜）。
     */
    @Select("SELECT MIN(m.id) FROM msg_message m JOIN msg_conversation c ON c.id = m.conversation_id"
            + " WHERE m.conversation_id = #{conversationId} AND m.sender_type = 'CUSTOMER'"
            + " AND m.id > COALESCE(c.store_last_read_message_id, 0)")
    Long selectMinUnreadCustomerId(@Param("conversationId") Long conversationId);

    /** 未读分组行（conversation_id → 未读数）。 */
    class UnreadRow {
        private Long conversationId;
        private long unread;

        public Long getConversationId() { return conversationId; }
        public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
        public long getUnread() { return unread; }
        public void setUnread(long unread) { this.unread = unread; }
    }
}
