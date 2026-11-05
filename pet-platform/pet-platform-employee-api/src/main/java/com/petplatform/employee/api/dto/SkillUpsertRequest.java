package com.petplatform.employee.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 技能字典新增/修改（第八阶段 §4.5：POST/PUT /api/v1/admin/skills，权限码 catalog:skill:manage）。 */
@Data
public class SkillUpsertRequest {

    @NotBlank(message = "技能编码必填")
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$", message = "技能编码须为大写字母开头的字母数字下划线组合，2-32位")
    private String code;

    @NotBlank(message = "技能名称必填")
    @Size(max = 50, message = "技能名称不超过50字")
    private String name;

    @Size(max = 32, message = "技能分类不超过32字")
    private String category;
}
