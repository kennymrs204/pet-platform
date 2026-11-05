package com.petplatform.employee.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 角色/技能全量替换请求（ADR-033）：语义同 ADR-010/027 的 PUT 全量——空数组=清空，null=参数缺失。 */
@Data
public class AssignRequest {

    /** 目标编码集合（角色 code 或技能 code，按端点而定）。允许为空列表=撤销全部。 */
    @NotNull(message = "编码列表必填（清空请传空数组）")
    private List<String> codes;
}
