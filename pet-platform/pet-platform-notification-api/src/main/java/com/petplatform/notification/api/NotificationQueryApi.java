package com.petplatform.notification.api;

import com.petplatform.common.result.PageResult;
import com.petplatform.notification.api.dto.NotificationView;

/**
 * 站内通知读侧（ADR-041）。表 Owner=notification，读只读本模块的 ntf_notification。
 *
 * <p><b>为什么每个方法都带 userId 而不是让调用方传条件</b>：收件人过滤是数据访问层的不变量
 * 而非"可选筛选参数"——放在端口签名上，装配层就没有"忘了加 where 导致全表可见"的可能。
 * 调用方一律传 {@code CurrentUserHolder.require().userId()}，绝不从请求参数取收件人。
 *
 * <p>C 端个人收件箱与 B 端门店收件箱是两套明确端口：
 * 个人收件箱固定 {@code store_id = 0}；门店收件箱固定 {@code store_id = 当前服务端门店上下文}。
 */
public interface NotificationQueryApi {

    /** 消息盒单页上限（同 BoardingQueryApi.HISTORY_MAX_PAGE_SIZE 的口径：常量在 api，夹取在 service）。 */
    int MAX_PAGE_SIZE = 50;

    /**
     * C 端个人消息盒：只查该用户 {@code store_id = 0} 的通知，按 created_at desc, id desc 分页。
     * 门店上下文下收到的通知（store_id &gt; 0）不得因共用同一账号而混入。
     */
    PageResult<NotificationView> pageForPersonalInbox(Long userId, int pageNum, int pageSize);

    /** C 端个人消息盒未读数：只统计该用户 {@code store_id = 0} 的未读行。 */
    long unreadCountForPersonalInbox(Long userId);

    /**
     * 某人在<b>指定门店上下文</b>里的消息（ADR-047，B 端收件箱）。
     *
     * <p>过滤只有一处：{@code store_id = #{storeId}}；门店 id 一律来自 token 的
     * {@code storeCtx}，绝不是请求参数。
     */
    PageResult<NotificationView> pageForUserInStore(Long userId, Long storeId, int pageNum, int pageSize);

    /**
     * 该门店上下文里的<b>铃铛</b>未读数（切店时红点跟着店走，不把两店消息糊成一个数）。
     * 口径＝排除 {@code CHAT_MESSAGE}（聊天未读另由 chatCount 计，避免铃铛 total 双计），
     * <b>不代表</b>门店消息中心列表的全部未读行——列表含 CHAT_MESSAGE，用
     * {@link #unreadCountInStoreForMessageList}。
     */
    long unreadCountInStore(Long userId, Long storeId);

    /**
     * 该门店上下文里<b>消息中心列表</b>的完整未读行数（含 {@code CHAT_MESSAGE}），与
     * {@link #pageForUserInStore} 渲染的行、{@code markAllReadInStore} 的更新范围一致。
     * 与 {@link #unreadCountInStore}（铃铛、排除 chat）刻意分列，不互相替换。
     */
    long unreadCountInStoreForMessageList(Long userId, Long storeId);
}
