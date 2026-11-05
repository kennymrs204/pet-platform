package com.petplatform.notification.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 站内通知视图（ADR-041）。
 *
 * <p><b>刻意不含 userId</b>：收件人就是调用方本人，查询口按当前登录用户过滤，
 * 视图里没有可泄露"别人的收件人是谁"的字段（同 {@code StoreRefundController.RefundItem} 不带 phone 的做法）。
 *
 * <p>{@code bizModule/bizId} 是 {@code ntf_notification.biz_ref} JSON 的平铺形态
 * （{"module":...,"bizId":...}），前端按 module 决定跳转目标；JSON 缺失或畸形时两字段为 null，
 * 表示"这条消息只读不可跳"，而不是整列表读失败。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationView {

    private Long id;
    /** REFUND_APPLIED / REFUND_SUCCESS / REFUND_REJECTED / ... */
    private String type;
    private String title;
    private String content;
    /** 跳转目标模块：appointment=可跳订单详情；store_refund 等为门店侧引用，C 端不跳 */
    private String bizModule;
    private Long bizId;
    /**
     * H5：{@code biz_ref.conversationId} 的平铺形态——chat 提醒的**深链定位键**
     * （bizId 是"未读段首条 messageId"，不兼作会话 id）；非 chat 类型恒 null。
     */
    private Long conversationId;
    /** read_at 是否已置；未读=true 的反面 */
    private boolean read;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
