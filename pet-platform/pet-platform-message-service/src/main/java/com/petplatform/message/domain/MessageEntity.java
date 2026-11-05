package com.petplatform.message.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息（msg_message，表 Owner=message，V11）。append-only：无删除/编辑，无 deleted 列。
 * uk_conv_client(conversation_id, client_msg_id) 是发送幂等的最终裁判；
 * idx_conv_id(conversation_id, id) 是会话内游标分页主路径。
 */
@Data
@TableName("msg_message")
public class MessageEntity {

    /** 雪花 ID——插入序即排序/游标键（同毫秒也不重不漏） */
    @TableId(type = IdType.INPUT)
    private Long id;

    private Long conversationId;
    /** CUSTOMER/STORE */
    private String senderType;
    private Long senderUserId;
    /** 发送时姓名快照（防改名/离职后显示丢失） */
    private String senderNameSnapshot;
    private String content;
    /** TEXT/IMAGE（V13；历史行默认 TEXT）；IMAGE 时 content 固定 '[图片]' */
    private String messageType;
    /** 仅 IMAGE：对象存储 key（u-{uid}/CHAT_IMAGE/…）——只存 key，不存 URL */
    private String mediaKey;
    /** 仅 IMAGE：MIME（服务端由 key 扩展名反推） */
    private String mediaContentType;
    /** 仅 IMAGE：字节数（发送时 HEAD 回查的真实大小，H2） */
    private Long mediaSize;
    private String bizModule;
    private Long bizId;
    /** 客户端幂等键（同会话唯一；服务端强制非空） */
    private String clientMsgId;
    private LocalDateTime createdAt;
}
