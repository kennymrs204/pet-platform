package com.petplatform.pet.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 全量更新宠物基础档案（ADR-014，PUT 语义：省略可空字段=写回 NULL，与 catalog 段一致）。
 * sizeCategory 由服务端按最新体重重算；vaccineSummary 只读；status 允许状态流转。
 */
@Data
public class PetUpdateRequest {

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

    private LocalDate birthday;

    private Boolean birthdayApprox;

    @DecimalMin(value = "0.01", message = "体重须大于0kg")
    @DecimalMax(value = "999.99", message = "体重超出可录入范围")
    private BigDecimal weightKg;

    @Size(max = 255, message = "头像地址不超过255字符")
    private String avatar;

    @Size(max = 500, message = "备注不超过500字")
    private String remark;

    @Pattern(regexp = "^(NORMAL|DECEASED|ARCHIVED)$", message = "状态仅支持NORMAL/DECEASED/ARCHIVED")
    private String status;
}
