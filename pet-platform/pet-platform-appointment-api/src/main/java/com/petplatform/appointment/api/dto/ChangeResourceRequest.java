package com.petplatform.appointment.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 换台位请求体（ADR-048 §8）。与 {@link AssignEmployeeRequest} 同理只带一个字段：
 * 换台位没有预约列写，version 无处可写；状态窗由领域服务在事务内重验，无需调用方带值。
 */
@Data
public class ChangeResourceRequest {

    /** 目标台位（sch_resource.id），必须属于本店、AVAILABLE、类型匹配该服务的资源要求。 */
    @NotNull(message = "resourceId 必填")
    private Long resourceId;
}
