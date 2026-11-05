package com.petplatform.user.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * C 端"我的"账号资料视图（P8 §4.2 GET /customer/profile，ADR-027）。
 * 仅回本人；phone 完整返回（登录凭证本人可见，非泄露面——B 端脱敏口径不适用此处）。
 * 与 ADR-017 的 UserProfileView 分开：那个供 B 端消费且禁带 default_contact_phone。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerProfileView {

    private Long userId;
    private String nickname;
    /** 对象存储 key（原样回显） */
    private String avatar;
    /** presigned 读取 URL（ADR-029；存储未启用或无头像时 null，前端二选一渲染） */
    private String avatarUrl;
    private String phone;
    /** NORMAL/DISABLED */
    private String status;
    private LocalDateTime registeredAt;
    private String defaultContactPhone;
    /** 顾客自选偏好门店（无跨域校验，ADR-027 §4） */
    private Long preferredStoreId;
    private Boolean marketingOptIn;
}
