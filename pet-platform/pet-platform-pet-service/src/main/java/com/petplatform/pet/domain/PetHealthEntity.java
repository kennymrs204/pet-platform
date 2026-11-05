package com.petplatform.pet.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.petplatform.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 宠物健康信息（pet_health_info，表 Owner：pet 模块，ADR-014）。
 * allergies/chronicDiseases 为 MySQL JSON 列的字符串形态（service 层 Jackson 往返），
 * 更新走"整行覆盖"语义故 ALWAYS。疫苗/诊疗明细不在此表，禁混入。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pet_health_info")
public class PetHealthEntity extends BaseEntity {

    private Long petId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String allergies;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String chronicDiseases;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String specialNotes;
    /** 写入方主角色（CUSTOMER/DOCTOR/CARETAKER/ADMIN…），后端写入（ADR-001-H 溯源） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String updatedByRole;
}
