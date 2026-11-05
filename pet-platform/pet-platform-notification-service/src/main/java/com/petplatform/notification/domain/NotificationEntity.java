package com.petplatform.notification.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知（ntf_notification，表 Owner=notification，第七阶段：P3 仅增+已读标记）。
 *
 * <p>不继承 BaseEntity：这张表没有 updated_at/deleted（append-only），
 * created_at 由 DB DEFAULT 维护。biz_ref 是 JSON 列，这里按字符串形态存取。
 */
@Data
@TableName("ntf_notification")
public class NotificationEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    /** 收件人所属门店上下文；0=非门店收件箱（V6 / ADR-047）。NOT NULL，落库前一律归一成 0，不给 NULL 进门的机会。 */
    private Long storeId;
    /** APPOINTMENT_PAID / REFUND_APPLIED / REFUND_SUCCESS / REFUND_REJECTED ... */
    private String type;
    private String title;
    private String content;
    /** {"module":...,"bizId":...} 弱引用，点击跳转用 */
    private String bizRef;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
