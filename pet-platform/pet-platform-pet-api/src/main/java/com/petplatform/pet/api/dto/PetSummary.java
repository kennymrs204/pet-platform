package com.petplatform.pet.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 宠物档案摘要（跨模块回显用：预约/托管列表补宠物名，不含主人联系方式等敏感字段）。 */
@Data
@Builder
public class PetSummary {

    private Long id;
    private String name;
    /** DOG/CAT/OTHER */
    private String species;
    private String breed;
    /** MALE/FEMALE/UNKNOWN */
    private String gender;
    private String avatar;
    /** NORMAL/DECEASED/ARCHIVED（ADR-014 向后兼容追加） */
    private String status;
    private BigDecimal weightKg;
    /** SMALL/MEDIUM/LARGE（ADR-014 向后兼容追加） */
    private String sizeCategory;
    private LocalDate birthday;
}
