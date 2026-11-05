package com.petplatform.message.service;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.message.api.ChatSide;
import com.petplatform.message.domain.ConversationEntity;
import com.petplatform.message.mapper.ConversationMapper;

/**
 * 会话归属守卫（service 层，防御深度）：所有"按会话 id 的操作"先过这里。
 *
 * <p>判定维度按侧不同，语义不可互替：
 * <ul>
 *   <li>CUSTOMER：actorId = 客户 userId，匹配 {@code customer_user_id}；</li>
 *   <li>STORE：actorId = 当前门店上下文 storeId，匹配 {@code store_id}（门店级共享），
 *       **不是员工 userId**——员工个人身份与"这家店能不能访问"是两件事，权限闸管前者、这里管后者。</li>
 * </ul>
 * 不属于自己一侧一律 10004（同枚举口径，不泄露会话真实归属）。
 */
final class ChatGuard {

    static final String SIDE_CUSTOMER = ChatSide.CUSTOMER;
    static final String SIDE_STORE = ChatSide.STORE;

    private ChatGuard() {
    }

    static ConversationEntity requireMine(ConversationMapper mapper, Long conversationId, String side, Long actorId) {
        if (!SIDE_CUSTOMER.equals(side) && !SIDE_STORE.equals(side)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "非法的会话参与方");
        }
        ConversationEntity conv = conversationId == null || actorId == null ? null : mapper.selectById(conversationId);
        if (conv == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        boolean mine = SIDE_CUSTOMER.equals(side)
                ? actorId.equals(conv.getCustomerUserId())
                : actorId.equals(conv.getStoreId());
        if (!mine) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        return conv;
    }
}
