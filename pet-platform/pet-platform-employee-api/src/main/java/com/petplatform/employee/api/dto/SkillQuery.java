package com.petplatform.employee.api.dto;

import com.petplatform.common.result.PageQuery;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 技能字典分页查询。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SkillQuery extends PageQuery {

    @Size(max = 50, message = "关键字不超过50字")
    private String keyword;

    @Size(max = 32, message = "分类不超过32字")
    private String category;
}
