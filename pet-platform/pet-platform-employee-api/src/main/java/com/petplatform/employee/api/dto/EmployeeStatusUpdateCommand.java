package com.petplatform.employee.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 平台侧变更员工全局状态（ADR-055，兑现 ADR-050 留给平台段的"停用/离职"独立裁决）。
 *
 * <p>三值语义：{@code SUSPENDED}=停职、{@code RESIGNED}=离职（本期与停职冻结效果相同，差别在
 * 纳编守卫的文案与数据语义）、{@code ACTIVE}=恢复在职。取值白名单在服务层把关（与 resolveRoles
 * 的字典校验同一风格）；DDL 注释里的 ON_LEAVE 全仓无消费方，不发明口径。
 *
 * <p>离开 ACTIVE 前服务层跑"每个其在职店长店的最后一名店长"守卫（ADR-035 同一条不变量）；
 * 权限缓存全上下文失效（evictAll）与清 refresh 白名单踢会话由控制器层在同一事务提交后编排。
 */
@Data
public class EmployeeStatusUpdateCommand {

    @NotBlank(message = "状态必填")
    private String status;
}
