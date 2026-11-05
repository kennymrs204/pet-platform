package com.petplatform.pet.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 宠物档案完整视图（C 端详情/列表行、B 端详情复用；ADR-014）。
 * Long id 经全局 Jackson 字符串化；vaccineSummary 为 medical 同步摘要，只读回显。
 */
@Data
@Builder
public class PetVO {

    private Long id;
    private String name;
    /** DOG/CAT/OTHER */
    private String species;
    private String breed;
    /** MALE/FEMALE/UNKNOWN */
    private String gender;
    private Boolean isNeutered;
    private LocalDate birthday;
    private Boolean birthdayApprox;
    private BigDecimal weightKg;
    /** SMALL/MEDIUM/LARGE，服务端按体重计算，只读 */
    private String sizeCategory;
    private String avatar;
    /** presigned 读取 URL（ADR-029；未启用存储或无头像时 null） */
    private String avatarUrl;
    private String remark;
    private String vaccineSummary;
    /** NORMAL/DECEASED/ARCHIVED */
    private String status;
    private LocalDateTime createdAt;
}
