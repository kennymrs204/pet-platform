package com.petplatform.appointment.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 店长分配/改派服务人员的请求体（ADR-048 §7）。
 *
 * <p>只有这一个字段是有原因的：分配这件事没有"顺便改时间/改台位"的语义，
 * 塞进同一个端点就会再造一处"一次请求改三样、失败一半"的一致性难题。
 */
@Data
public class AssignEmployeeRequest {

    /** 目标服务人员（emp_employee.id），必须是本店在职、当日有班、技能覆盖该服务的人。 */
    @NotNull(message = "employeeId 必填")
    private Long employeeId;
}
