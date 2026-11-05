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
 * 创建宠物档案（ADR-014）。sizeCategory/vaccineSummary/status 不接受客户端传入：
 * 尺寸由服务端按体重计算，疫苗摘要由 medical 事件同步，新建档状态恒 NORMAL。
 */
@Data
public class PetCreateRequest {

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
}
