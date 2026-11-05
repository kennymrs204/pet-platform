package com.petplatform.pet.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.petplatform.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 宠物档案（pet_pet，表 Owner：pet 模块）。
 * 可空业务字段 updateStrategy=ALWAYS：PUT 为全量提交，省略=写回 NULL（catalog 段同约定）。
 * vaccine_summary 由 medical 事件同步维护，本实体更新路径不回写该列（保持默认 NOT_NULL 且 Service 不赋值 loaded 外内容）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pet_pet")
public class PetEntity extends BaseEntity {

    private String name;
    /** DOG/CAT/OTHER */
    private String species;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String breed;
    /** MALE/FEMALE/UNKNOWN */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String gender;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Boolean isNeutered;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate birthday;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Boolean birthdayApprox;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal weightKg;
    /** SMALL/MEDIUM/LARGE，服务端计算列（ADR-014） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String sizeCategory;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String avatar;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String remark;
    /** 最近接种摘要，medical 事件同步（只读回显） */
    private String vaccineSummary;
    /** NORMAL/DECEASED/ARCHIVED */
    private String status;
    /** 门店代建来源（ADR-058 增量；跨模块ID无FK）。C 端自建恒 null；B 端可见范围据此 ∪ 本店代建 */
    private Long originStoreId;
}
