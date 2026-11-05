package com.petplatform.employee.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 平台侧编辑员工全局档案（ADR-050，兑现 ADR-033 §2 留给平台段的那半句）。
 *
 * <p>姓名/工号/入职日是 {@code emp_employee} 这一行上的**跨店共享字段**——门店端点刻意不提供
 * （ADR-033 §2：两个店长互改必然互相吞掉），唯一写权在平台段（闸 {@code store:manage}）。
 *
 * <p>提交语义与 ADR-010/027/033 的 PUT 全量同族：三个字段都要提交，{@code employeeNo}/{@code hiredAt}
 * 传 null = 清空该列（两列本就可空）；{@code name} 必填非空白（NOT NULL 列）。
 * **刻意不含 status（在职/离职）**：停用要联动"清 refresh 白名单踢下线 + 撤码"（revokeAll），
 * 是平台停用端点的独立裁决（ADR-033 遗留）——**已于 ADR-055 另立
 * {@code PUT /admin/employees/{id}/status} 端点落地**，不搭这趟车。
 */
@Data
public class EmployeeProfileUpdateCommand {

    @NotBlank(message = "姓名必填")
    @Size(max = 50, message = "姓名不超过 50 字")
    private String name;

    @Size(max = 32, message = "工号不超过 32 字")
    private String employeeNo;

    /** 入职日；null = 清空（列可空，页面对空值显示「—」） */
    private LocalDate hiredAt;
}
