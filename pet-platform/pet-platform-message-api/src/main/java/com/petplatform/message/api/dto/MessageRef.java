package com.petplatform.message.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 消息域出参（表 Owner：message）。senderUserId 是服务端按鉴权身份写入的快照，
 * 出参是否暴露给前端由 web 层决定（第一版 web 出参不含 senderUserId）。
 * 同理 mediaKey 是存储域引用：web 层只出**动态签发的 mediaUrl**，不出裸 key。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageRef {

    private Long id;
    private Long conversationId;
    /** CUSTOMER/STORE */
    private String senderType;
    private Long senderUserId;
    /** 发送时姓名快照（C 端=顾客昵称；B 端=员工姓名） */
    private String senderNameSnapshot;
    private String content;
    /** TEXT/IMAGE（历史行恒为 TEXT——V13 DEFAULT） */
    private String messageType;
    /** 仅 IMAGE：对象存储 key（读侧由业务出口签 presigned GET；本层不产出 URL） */
    private String mediaKey;
    private String bizModule;
    private Long bizId;
    private LocalDateTime createdAt;
}
