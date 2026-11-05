package com.petplatform.employee.api.dto;

import lombok.Builder;
import lombok.Data;

/** 技能字典摘要（供 catalog 回显服务所需技能、schedule 做技能匹配）。 */
@Data
@Builder
public class SkillView {

    private Long id;
    private String code;
    private String name;
    private String category;
}
