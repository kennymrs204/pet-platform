package com.petplatform.notification.api.dto;

/**
 * 一条站内通知的投递请求（ADR-037 §5）。
 *
 * <p>{@code bizModule/bizId} 是弱引用（对应 ntf_notification.biz_ref JSON 列），
 * 前端点击时按模块跳转，不做外键——通知是不可变历史，业务对象被删也不该让通知写不进去。
 *
 * @param userId  收件人（usr_user.id）
 * @param storeId 该收件人的<b>门店上下文</b>（ADR-047）：门店消息填产生它的门店，B 端按
 *                {@code CurrentUserHolder.storeCtx()} 过滤才看得到；<b>0=非门店收件箱</b>
 *                （C 端个人消息），与 {@code AuthPrincipal.storeCtx=0} 同一哨兵。
 *                多店任职的员工（实测 DemoManager 同属 9111/9112）靠这一列才不会把两店提醒糊成一个红点。
 * @param type    事件类型，如 REFUND_APPLIED / REFUND_SUCCESS / REFUND_REJECTED
 * @param title   ≤100 字
 * @param content ≤500 字
 * @param conversationId H5：chat 提醒的**深链定位键**（bizId 仍是"未读段首条 messageId"，
 *                不兼作会话 id）——仅当 bizModule=chat 时非空，toRow 会额外写进 biz_ref JSON；
 *                其余类型恒 null、JSON 形状不变。
 */
public record NotificationPush(
        Long userId,
        Long storeId,
        String type,
        String title,
        String content,
        String bizModule,
        Long bizId,
        Long conversationId
) {

    /**
     * 7 参兼容构造（既有门店消息调用方零改动）：{@code conversationId=null}，biz_ref JSON 形状不变。
     */
    public NotificationPush(Long userId, Long storeId, String type, String title, String content,
                            String bizModule, Long bizId) {
        this(userId, storeId, type, title, content, bizModule, bizId, null);
    }

    /**
     * 个人（非门店）消息的简写形态：{@code storeId=0}。
     *
     * <p>刻意保留 6 参构造而不是把全部调用方都改成 7 参：客户消息<b>本来就没有门店收件箱</b>，
     * 强要调用方填 0 只会让"这条消息属不属于某个店的收件箱"这个问题看起来像个门店编号。
     * 反过来，要发给员工的门店消息必须走 7 参形态——少填一个参数就编译不过。
     */
    public NotificationPush(Long userId, String type, String title, String content,
                            String bizModule, Long bizId) {
        this(userId, 0L, type, title, content, bizModule, bizId, null);
    }

    /** 收件人所属门店；未显式给值时按 0（非门店收件箱）处理，绝不让 NULL 进 NOT NULL 列。 */
    public long storeIdOrZero() {
        return storeId == null ? 0L : storeId;
    }
}
