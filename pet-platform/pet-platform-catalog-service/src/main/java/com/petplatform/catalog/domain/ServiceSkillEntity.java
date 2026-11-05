package com.petplatform.catalog.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.petplatform.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 服务所需技能声明（cat_service_skill）：required=false 为可选加分技能。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cat_service_skill")
public class ServiceSkillEntity extends BaseEntity {

    private Long serviceId;
    private Long skillId;

    /** 列名 is_required；显式绑定避免 Lombok/反射对 isXxx 布尔属性的歧义。 */
    @TableField("is_required")
    private Boolean required;
}
