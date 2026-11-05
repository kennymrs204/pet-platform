package com.petplatform.message.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话域出参（表 Owner：message）。字段与 msg_conversation 同构；
 * storeName / 客户姓名等展示信息由 app 装配层回填，不在本层聚合（Owner 边界）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationRef {

    private Long id;
    private Long storeId;
    private Long customerUserId;
    /** 首建来源业务（弱引用）——创建后不变，≠"当前讨论的订单" */
    private String originBizModule;
    private Long originBizId;
    private Long lastMessageId;
    private LocalDateTime lastMessageAt;
    private String lastMessagePreview;
    private Long customerLastReadMessageId;
    private Long storeLastReadMessageId;
    private LocalDateTime createdAt;
}
