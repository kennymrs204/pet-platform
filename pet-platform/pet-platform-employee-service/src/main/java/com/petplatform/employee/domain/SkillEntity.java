package com.petplatform.employee.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.petplatform.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("emp_skill")
public class SkillEntity extends BaseEntity {

    private String code;
    private String name;
    /** GROOMING/WALK/BOARDING/MEDICAL... */
    private String category;
}
