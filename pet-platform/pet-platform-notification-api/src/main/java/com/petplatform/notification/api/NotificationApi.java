package com.petplatform.notification.api;

import com.petplatform.notification.api.dto.NotificationPush;

/**
 * 站内通知写入口（表 Owner=notification，ntf_notification 已在 V1 建好）。
 *
 * <p><b>本期只做"入库"这一半</b>（ADR-037 §5）：没有未读计数端点、没有已读标记端点、
 * 也没有任何推送通道（App/短信/微信模板消息全未接）。所以写进去的通知当前**在界面上看不到**，
 * 它的价值是把"该发什么消息、发给谁、什么时候发"这条业务判断固化在代码里并留下审计行，
 * 等 notification 段真正落地时只补投递与查询，不必回头改退款/订单链。
 *
 * <p>调用方=app 装配层的监听器（业务模块不依赖本接口，矩阵零新增边）。
 */
public interface NotificationApi {

    /** 落一条站内通知。收件人不存在的推送会被丢弃（不回查账号状态）。 */
    void push(NotificationPush push);

    /**
     * 幂等落一条站内通知：<b>同一收件人 + 同一类型 + 同一业务引用（module+bizId）只落一条</b>，
     * 重复调用静默丢弃（返回 false）。
     *
     * <p>为什么单独开一个方法而不是给 {@link #push} 加去重：退款链上的消息**故意允许多条同名**——
     * 同一预约可以"申请→被拒→再申请"，那时 {@code (user, REFUND_APPLIED, appointment)} 会重复出现，
     * 若统一去重就会吞掉第二条合法通知（ADR-043 的探针实测过这个序列）。
     * 所以"要不要幂等"由调用方按业务语义决定：一次性事实（下单成功）用 {@code pushOnce}，
     * 可重复发生的事实（退款申请）用 {@code push}。
     *
     * <p>与 {@code push} 同样是**主链旁路**：任何异常降级为 warn，绝不影响业务事务。
     *
     * @return true=本次真的落了一条；false=已存在同语义通知，按幂等丢弃
     */
    boolean pushOnce(NotificationPush push);

    /**
     * H5：读会话联动清提醒——把该收件人在该门店下、biz_ref 指向该 conversation 的
     * 未读 {@code CHAT_MESSAGE} 提醒全部标已读（幂等；返回本次实际变更条数，参数不全返回 0）。
     *
     * <p>提醒行按收件人各自持有：谁读会话清谁的提醒（门店已读游标共享，但"已读"观感以本人
     * 消息中心为准）。旁路语义：内部降级 warn 吞错，调用方还应再兜一层，绝不影响聊天 read 主链。
     */
    int markChatRemindersRead(Long userId, Long storeId, Long conversationId);
}
