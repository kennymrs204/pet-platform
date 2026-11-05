package com.petplatform.catalog.api.dto;

import lombok.Builder;
import lombok.Data;

/** 技能引用（catalog 不直查 emp_skill，技能名经 employee-api 取回后组装；避免 api 依赖 api）。 */
@Data
@Builder
public class SkillRef {

    private Long skillId;
    private String code;
    private String name;
    /** false=可选加分技能（cat_service_skill.is_required=0）。 */
    private boolean required;
}
