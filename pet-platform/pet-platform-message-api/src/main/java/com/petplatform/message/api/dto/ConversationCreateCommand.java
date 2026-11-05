package com.petplatform.message.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * get-or-create 入参（幂等）：同 (storeId, customerUserId) 重复调用返回同一会话。
 * originBiz* 只在**首次创建**时写入；已有会话时被忽略（不覆盖历史来源）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationCreateCommand {

    private Long storeId;
    private Long customerUserId;
    /** 首建来源业务类型（如 appointment）；可空 */
    private String originBizModule;
    private Long originBizId;
}
