package com.petplatform.pet.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * B 端门店受限基础资料更新请求（本批次编辑资料）。
 *
 * <p>刻意不包含 avatar/status/owner/originStoreId/sizeCategory/vaccineSummary：
 * 这些字段由其他流程或服务端维护，B 端编辑不能覆盖。与 C 端全量 {@link PetUpdateRequest}
 * 分开，避免 B 端误清空头像或改动状态。
 */
@Data
public class PetBaseUpdateRequest {

    @NotBlank(message = "宠物昵称必填")
    @Size(max = 50, message = "宠物昵称不超过50字")
    private String name;

    @NotBlank(message = "物种必填")
    @Pattern(regexp = "^(DOG|CAT|OTHER)$", message = "物种仅支持DOG/CAT/OTHER")
    private String species;

    @Size(max = 50, message = "品种不超过50字")
    private String breed;

    @Pattern(regexp = "^(MALE|FEMALE|UNKNOWN)$", message = "性别仅支持MALE/FEMALE/UNKNOWN")
    private String gender;

    private Boolean isNeutered;

    @PastOrPresent(message = "生日不能晚于今天")
    private LocalDate birthday;

    private Boolean birthdayApprox;

    @DecimalMin(value = "0.01", message = "体重须大于0kg")
    @DecimalMax(value = "999.99", message = "体重超出可录入范围")
    private BigDecimal weightKg;

    @Size(max = 500, message = "备注不超过500字")
    private String remark;
}
