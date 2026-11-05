package com.petplatform.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.message.domain.ConversationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话读写（msg_conversation，表 Owner=message）。
 * 所有"按会话操作"的 UPDATE 都把归属条件写进 WHERE（C 端 customer_user_id / B 端 store_id）——
 * 归属校验与写入同一条语句，不依赖上层先查后判。
 */
@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {

    /** get-or-create 的定位键（uk_store_customer）。 */
    @Select("SELECT * FROM msg_conversation WHERE store_id = #{storeId} AND customer_user_id = #{customerUserId}")
    ConversationEntity selectByStoreCustomer(@Param("storeId") Long storeId, @Param("customerUserId") Long customerUserId);

    /* ---- 列表（last_message_at DESC, id DESC；无消息的新会话 last_message_at 为 NULL 自然排最后） ---- */

    @Select("SELECT * FROM msg_conversation WHERE customer_user_id = #{customerUserId}"
            + " ORDER BY last_message_at DESC, id DESC LIMIT #{offset}, #{size}")
    List<ConversationEntity> selectCustomerPage(@Param("customerUserId") Long customerUserId,
                                                @Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM msg_conversation WHERE customer_user_id = #{customerUserId}")
    long countCustomer(@Param("customerUserId") Long customerUserId);

    @Select("SELECT * FROM msg_conversation WHERE store_id = #{storeId}"
            + " ORDER BY last_message_at DESC, id DESC LIMIT #{offset}, #{size}")
    List<ConversationEntity> selectStorePage(@Param("storeId") Long storeId,
                                             @Param("offset") int offset, @Param("size") int size);

    /** H1 服务端搜索：本店全量 refs（不分页）——排序与 {@link #selectStorePage} 完全一致。 */
    @Select("SELECT * FROM msg_conversation WHERE store_id = #{storeId}"
            + " ORDER BY last_message_at DESC, id DESC")
    List<ConversationEntity> selectStoreAll(@Param("storeId") Long storeId);

    @Select("SELECT COUNT(*) FROM msg_conversation WHERE store_id = #{storeId}")
    long countStore(@Param("storeId") Long storeId);

    /* ---- 最近消息摘要（派生缓存，与消息插入同事务） ---- */

    /**
     * 条件更新：只有"更新的消息 id"才允许覆盖——并发两个发送（小 id 后提交）不会把摘要倒回旧消息。
     * 0 行=不是最新（幂等语义，不是失败）。
     */
    @Update("UPDATE msg_conversation SET last_message_id = #{messageId}, last_message_at = #{at},"
            + " last_message_preview = #{preview}"
            + " WHERE id = #{id} AND (last_message_id IS NULL OR last_message_id < #{messageId})")
    int bumpLastMessage(@Param("id") Long id, @Param("messageId") Long messageId,
                        @Param("at") LocalDateTime at, @Param("preview") String preview);

    /* ---- 已读游标（快照式，GREATEST 幂等且只前进） ---- */

    /** C 端：WHERE 带 customer_user_id——不是这个客户的会话 0 行（调用方据此 10004）。 */
    @Update("UPDATE msg_conversation"
            + " SET customer_last_read_message_id = GREATEST(COALESCE(customer_last_read_message_id, 0), #{maxMessageId})"
            + " WHERE id = #{id} AND customer_user_id = #{customerUserId}")
    int markCustomerRead(@Param("id") Long id, @Param("customerUserId") Long customerUserId,
                         @Param("maxMessageId") long maxMessageId);

    /** B 端（门店级共享已读）：WHERE 带 store_id——不属于本店的会话 0 行。 */
    @Update("UPDATE msg_conversation"
            + " SET store_last_read_message_id = GREATEST(COALESCE(store_last_read_message_id, 0), #{maxMessageId})"
            + " WHERE id = #{id} AND store_id = #{storeId}")
    int markStoreRead(@Param("id") Long id, @Param("storeId") Long storeId,
                      @Param("maxMessageId") long maxMessageId);
}
