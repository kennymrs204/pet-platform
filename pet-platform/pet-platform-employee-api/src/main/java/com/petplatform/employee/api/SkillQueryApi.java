package com.petplatform.employee.api;

import com.petplatform.employee.api.dto.SkillView;

import java.util.Collection;
import java.util.List;

/**
 * 技能字典查询（第七阶段 §表归属：emp_skill 由 employee 模块唯一 Owner，
 * catalog 的 cat_service_skill 声明与 schedule 的员工技能匹配都只能经本接口读，
 * 不得跨模块直查 emp_* 表——ArchUnit 规则 3 固化）。
 */
public interface SkillQueryApi {

    /** 入参中确实存在的技能 id；不存在的被剔除。入参为空返回空列表。 */
    List<Long> filterExistingIds(Collection<Long> skillIds);

    /** 按 id 批量取技能摘要；不存在的 id 不出现在结果中。 */
    List<SkillView> listByIds(Collection<Long> skillIds);
}
