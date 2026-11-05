package com.petplatform.user.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 账号资料更新（P8 §4.2 PUT /customer/profile，ADR-027 §3 全量提交语义）：
 * null 是合法值=清空（ADR-010 §6 同族约定）；phone/status 不在体内——手机号变更属换绑流程，本期不做。
 */
@Data
public class ProfileUpdateRequest {

    /** 可空=清空回落默认昵称展示；非空时 1..50 */
    @Size(max = 50, message = "昵称最多50字")
    private String nickname;

    @Size(max = 255, message = "头像地址最多255字符")
    private String avatar;

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "默认联系电话须为手机号或空")
    @Size(max = 20, message = "默认联系电话过长")
    private String defaultContactPhone;

    private Long preferredStoreId;

    @NotNull(message = "marketingOptIn必填")
    private Boolean marketingOptIn;
}
