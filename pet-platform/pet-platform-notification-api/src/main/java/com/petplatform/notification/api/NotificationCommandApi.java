package com.petplatform.notification.api;

/**
 * 站内通知写侧之"已读"（ADR-041）。与 {@link NotificationApi}（投递/落库）分开：
 * 一个是业务链的旁路写入（同步、吞异常、绝不能拖垮主链），一个是用户主动的状态变更
 * （必须报错——已读没生效而前端以为生效，比失败更糟）。
 *
 * <p>C 端个人收件箱固定 {@code store_id = 0}；B 端门店收件箱固定当前服务端门店上下文。
 */
public interface NotificationCommandApi {

    /**
     * 标记 C 端个人收件箱单条已读。作用域固定为
     * {@code user_id = 当前用户 AND store_id = 0}。
     *
     * <p><b>幂等</b>：已读过的再次调用不报错、不改写首个 read_at。
     * 归属校验与更新使用同一作用域；消息不存在、属于他人或属于门店收件箱，一律
     * {@code CommonErrorCode.NOT_FOUND}（10004），不泄露消息是否存在。
     */
    void markReadInPersonalInbox(Long userId, Long notificationId);

    /** C 端个人收件箱全部已读：只清 {@code user_id = 当前用户 AND store_id = 0} 的未读行。 */
    int markAllReadInPersonalInbox(Long userId);

    /**
     * 标记 B 端当前门店收件箱单条已读。作用域固定为
     * {@code user_id = 当前用户 AND store_id = 当前服务端门店上下文 AND id = 通知 id}。
     *
     * <p>门店 id 必须来自认证上下文，不接受前端传入；更新与归属探针使用同一门店作用域。
     * 幂等语义与个人收件箱一致。
     */
    void markReadInStore(Long userId, Long storeId, Long notificationId);

    /**
     * 只清<b>指定门店上下文</b>里的未读，返回本次实际变更条数（ADR-047）。
     *
     * @param storeId 门店上下文；0 或非正数时一行都不动（0 是"非门店收件箱"哨兵，不属于任何店）
     */
    int markAllReadInStore(Long userId, Long storeId);
}
