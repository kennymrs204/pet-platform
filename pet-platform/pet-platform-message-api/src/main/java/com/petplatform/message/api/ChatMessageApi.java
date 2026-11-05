package com.petplatform.message.api;

import com.petplatform.message.api.dto.MessagePage;
import com.petplatform.message.api.dto.MessageRef;
import com.petplatform.message.api.dto.MessageSendCommand;

/**
 * 消息读写（表 Owner：message）。append-only：无删除/编辑/撤回（第一版刻意不做）。
 * 发送幂等以 (conversation_id, client_msg_id) 唯一约束为最终裁判；重复提交返回**原消息**
 * （幂等成功语义，不是报错）。发送方身份（senderType/senderUserId/senderName）由调用方
 * 按鉴权身份解析后传入，本层不读请求上下文。
 */
public interface ChatMessageApi {

    /**
     * 发送纯文本消息（≤500，服务端 trim 后非空）。
     * 会话归属校验失败 → 10004；内容非法 → 10001；重复 clientMsgId → 返回原消息。
     * 与"会话最近消息摘要/last_message_*"的更新同事务（消息插入成功而摘要没更新是不可能的）。
     */
    MessageRef send(MessageSendCommand command);

    /**
     * 会话内消息（稳定游标，两个方向共用本口）：
     * <ul>
     *   <li>无游标：最近一页（id DESC，最新在前）；</li>
     *   <li>{@code beforeId}：id &lt; beforeId 的更早消息（id DESC）——翻历史；</li>
     *   <li>{@code afterId}：id &gt; afterId 的更新消息（**id ASC**，时间序）——增量拉新，
     *       调用方取本页最后一条 id 作为下一次 afterId；</li>
     * </ul>
     * beforeId 与 afterId **互斥**（同传 → 10001）。limit 钳 [1,50]（默认 20）；
     * hasMore 针对当前方向：DESC 方向=更早还有，ASC 方向=更新还有。
     * 排序键与游标键同为消息 id（雪花插入序），同毫秒也不重不漏。归属校验失败 → 10004。
     */
    MessagePage messages(Long conversationId, String side, Long actorId, Long beforeId, Long afterId, Integer limit);

    /**
     * H5（T1）：门店视角该会话**最小未读客户消息 id**（无未读或会话不存在 → null）。
     * 口径＝现有 storeUnread* 系列（对侧=客户发送 ∧ id &gt; 门店已读游标）；
     * 订阅方用"它是否等于本次落库的 messageId"判定"本消息是否为当前未读段首条"——
     * 一个未读段只有首条会触发门店站内提醒。
     */
    Long minUnreadCustomerMessageId(Long conversationId);
}
