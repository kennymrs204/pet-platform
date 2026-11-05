package com.petplatform.message.api;

import com.petplatform.common.result.PageResult;
import com.petplatform.message.api.dto.ConversationCreateCommand;
import com.petplatform.message.api.dto.ConversationRef;

import java.util.List;
import java.util.Map;

/**
 * 会话读写（表 Owner：message，两张表 msg_conversation/msg_message）。
 * C 端与 B 端共用同一套会话模型：会话 = (store_id, customer_user_id) 长期唯一，
 * 不因订单多开、不因门店换人重开；不同门店严格隔离（store_id 进 uk 与全部查询条件）。
 *
 * <p><b>归属校验在本层</b>（防御深度）：所有按会话 id 的操作都以"该侧身份"作 WHERE 条件，
 * 不属于自己（C 端：customer_user_id ≠ 调用者；B 端：store_id ≠ 调用者门店上下文）一律
 * 10004 同枚举口径——调用方（web 层）不得把"前端传入的会话 id"当作已授权的事实。
 */
public interface ChatConversationApi {

    /**
     * get-or-create（幂等）：同 (storeId, customerUserId) 重复调用返回同一会话。
     * 并发创建由 uk_store_customer 唯一约束归一（服务端冲突回查，不依赖"先查再插"单点判断）。
     * originBiz* 只在首次创建时写入；已有会话时忽略（历史来源不被新的入口覆盖）。
     */
    ConversationRef getOrCreate(ConversationCreateCommand command);

    /**
     * 单会话详情（只读，**不清未读**——已读只由 markRead 按明确时机处理）。
     * 归属校验同全部按 id 的操作：不属于自己一侧一律 10004（防枚举）。
     */
    ConversationRef conversation(Long conversationId, String side, Long actorId);

    /**
     * 标记已读（幂等，门店级共享已读）：写"当刻该会话最大消息 id"快照——
     * 快照之后到达的新消息 id 更大，必然保持未读（并发发送×已读不丢未读）。
     * 归属校验失败 → 10004。
     */
    void markRead(Long conversationId, String side, Long actorId);

    /** C 端我的会话分页（customer_user_id=调用者；last_message_at desc→id desc；pageSize 钳 [1,100]）。 */
    PageResult<ConversationRef> customerConversations(Long customerUserId, int pageNum, int pageSize);

    /** B 端本店会话分页（store_id=调用者门店；排序同上）。 */
    PageResult<ConversationRef> storeConversations(Long storeId, int pageNum, int pageSize);

    /**
     * H1 服务端搜索：本店**全量**会话（不分页）——排序与 {@link #storeConversations} 完全一致
     * （last_message_at desc→id desc）。过滤与分页在调用方（app 层）完成，本层只负责
     * "当前门店范围内、排序稳定"的数据出口（归属由 store_id 条件天然保证）。
     */
    List<ConversationRef> allStoreConversations(Long storeId);

    /** C 端聊天未读汇总（该客户全部会话，对侧=STORE 且 id>已读游标）。 */
    long customerUnreadTotal(Long customerUserId);

    /** B 端聊天未读汇总（本店全部会话，对侧=CUSTOMER 且 id>已读游标）。 */
    long storeUnreadTotal(Long storeId);

    /** 列表装配用：C 端每个会话的未读数（只含未读 > 0 的会话）。 */
    Map<Long, Long> customerUnreadByConversation(Long customerUserId);

    /** 列表装配用：B 端每个会话的未读数（只含未读 > 0 的会话）。 */
    Map<Long, Long> storeUnreadByConversation(Long storeId);
}
