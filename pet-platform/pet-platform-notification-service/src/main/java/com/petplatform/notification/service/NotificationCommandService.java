package com.petplatform.notification.service;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.notification.api.NotificationCommandApi;
import com.petplatform.notification.mapper.NotificationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 已读标记（ADR-041）。与投递侧 {@link NotificationService} 分家的理由写在该接口注释里：
 * 这条链上的失败**必须**报错，用户的"已读"不能静默丢失。
 */
@Slf4j
@Service
public class NotificationCommandService implements NotificationCommandApi {

    private final NotificationMapper notificationMapper;

    public NotificationCommandService(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    /**
     * C 端个人收件箱单条已读：更新与归属探针都固定 {@code store_id = 0}。
     * 条件更新 0 行有两种可能，靠同一作用域的归属探针分辨：
     * 消息确实是你的个人消息且已读 → 幂等返回；否则 → 10004。
     * 不用 10003：那等于告诉调用方"这条消息存在但不归你"。
     */
    @Override
    @Transactional
    public void markReadInPersonalInbox(Long userId, Long notificationId) {
        if (userId == null || notificationId == null) {
            throw BusinessException.of(CommonErrorCode.PARAM_INVALID);
        }
        if (notificationMapper.updateMarkReadPersonal(userId, notificationId) > 0) {
            return;
        }
        if (notificationMapper.countOwnedPersonal(userId, notificationId) == 0) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        log.debug("重复标记个人消息已读，幂等忽略 userId={} id={}", userId, notificationId);
    }

    @Override
    @Transactional
    public int markAllReadInPersonalInbox(Long userId) {
        if (userId == null) {
            throw BusinessException.of(CommonErrorCode.PARAM_INVALID);
        }
        return notificationMapper.updateMarkAllReadPersonal(userId);
    }

    /**
     * B 端门店收件箱单条已读：UPDATE 与归属探针都固定当前服务端门店上下文。
     * 跨门店 id 既不能命中更新，也不会被归属探针认领，统一 10004。
     */
    @Override
    @Transactional
    public void markReadInStore(Long userId, Long storeId, Long notificationId) {
        if (userId == null || notificationId == null) {
            throw BusinessException.of(CommonErrorCode.PARAM_INVALID);
        }
        if (storeId == null || storeId <= 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少有效的门店上下文");
        }
        if (notificationMapper.updateMarkReadInStore(userId, storeId, notificationId) > 0) {
            return;
        }
        if (notificationMapper.countOwnedInStore(userId, storeId, notificationId) == 0) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        log.debug("重复标记门店消息已读，幂等忽略 userId={} storeId={} id={}",
                userId, storeId, notificationId);
    }

    /**
     * 门店上下文的全量已读（ADR-047）。空参照旧 10001；{@code storeId<=0} 返回 0 而不是"清掉全部"——
     * 后者会把这个人所有门店的未读连客户个人消息一起标读，是一次用户没要求的跨上下文写入。
     */
    @Override
    @Transactional
    public int markAllReadInStore(Long userId, Long storeId) {
        if (userId == null || storeId == null) {
            throw BusinessException.of(CommonErrorCode.PARAM_INVALID);
        }
        if (storeId <= 0L) {
            return 0;
        }
        return notificationMapper.updateMarkAllReadInStore(userId, storeId);
    }
}
