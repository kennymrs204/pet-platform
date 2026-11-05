package com.petplatform.message.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发送消息入参（幂等）。senderType/senderUserId/senderName 由 app 装配层按**鉴权身份**解析后传入，
 * 一律不来自前端请求体（发送方身份不可被调用方指定）。
 * 同 (conversationId, clientMsgId) 重复提交返回原消息，不重复插入。
 *
 * <p>两个"身份"字段刻意分开（语义不同，不可互替）：
 * <ul>
 *   <li>{@code senderUserId} = 落库的真实发送人（B 端=员工的 usr_user.id，用于姓名快照与追溯）；</li>
 *   <li>{@code senderStoreId} = 归属校验维度（B 端=当前门店上下文的 storeId；C 端传 null，
 *       归属按 senderUserId 匹配 customer_user_id）。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageSendCommand {

    private Long conversationId;
    /** CUSTOMER/STORE */
    private String senderType;
    /** 真实发送人 usr_user.id（服务端解析，非前端传入） */
    private Long senderUserId;
    /** B 端归属校验用门店上下文（CUSTOMER 侧为 null） */
    private Long senderStoreId;
    /** 发送时姓名快照（服务端解析：C 端=顾客昵称，B 端=员工姓名） */
    private String senderName;
    private String content;
    /**
     * 消息类型 TEXT/IMAGE（可空=TEXT，旧客户端零改动）。IMAGE 时 content 固定 '[图片]'，
     * 且 {@code mediaKey} 必填；TEXT 携带 mediaKey 一律 10001。
     */
    private String messageType;
    /**
     * 仅 IMAGE：对象存储 key（形态 u-{uid}/CHAT_IMAGE/{yyyyMM}/{雪花}.{ext}）。
     * 服务端校验：形态 → 场景=CHAT_IMAGE → **桶主==senderUserId**（防把他人对象塞进消息，
     * 读取侧会替他人桶签名）——不接受任何 URL。
     */
    private String mediaKey;
    /** 客户端幂等键（服务端强制非空，≤64） */
    private String clientMsgId;
    private String bizModule;
    private Long bizId;
}
