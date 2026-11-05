package com.petplatform.message.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 门店-客户长期会话（msg_conversation，表 Owner=message，V11）。
 *
 * <p>不继承 BaseEntity：无 deleted 列（第一版无删除/归档；V11 建表即此形状）。
 * 未读是**游标式**（customer/store_last_read_message_id）——没有计数器列可漂移；
 * last_message_* 是派生缓存，与消息插入同事务维护（不只在内存拼）。
 */
@Data
@TableName("msg_conversation")
public class ConversationEntity {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long storeId;
    private Long customerUserId;
    /** 首建来源业务（弱引用）——创建后不再变更 */
    private String originBizModule;
    private Long originBizId;
    private Long lastMessageId;
    private LocalDateTime lastMessageAt;
    private String lastMessagePreview;
    private Long customerLastReadMessageId;
    private Long storeLastReadMessageId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
