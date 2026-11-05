package com.petplatform.boarding.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 在住单指派主责护理员（补派/改派）。仅一个字段：宠物/笼位/日期都不在改动范围内，
 * 不给前端任何可注入的多余面（与 {@link CheckinRequest} 分开的理由同 ADR-016 补建入口无请求体）。
 */
@Data
public class CaretakerAssignRequest {

    @NotNull(message = "请选择护理员")
    private Long caretakerId;
}
