package com.petplatform.user.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 客户档案视图（表 Owner：user；ADR-017 §5 客户列表/详情数据源）。
 * 注意：本视图带**完整 phone**，仅供服务端内部脱敏后出参——
 * 任何 B 端响应必须经 `phoneMasked` 出口，禁止把本字段原样下发（ADR-017 §3）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileView {

    private Long userId;
    private String nickname;
    private String avatar;
    private String phone;
    /** NORMAL/DISABLED */
    private String status;
    private LocalDateTime registeredAt;
    /** 顾客自选偏好门店（非归属关系，usr_customer_profile.preferred_store_id）；无档案为 null */
    private Long preferredStoreId;
    private Boolean marketingOptIn;
    /** 默认联系电话（ADR-027；手机号或空）——门店侧编辑回显用 */
    private String defaultContactPhone;
    /** 门店侧客户备注（ADR-058；C 端不展示） */
    private String remark;
}
