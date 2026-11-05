package com.petplatform.employee.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 员工纳编请求（ADR-033，P8 §4.3 规划的 {@code POST /store/employees}）。
 *
 * <p>只要手机号不要 userId：员工身份必须挂在**已注册且已通过短信验证**的登录主体上
 * （{@code emp_employee.user_id NOT NULL + uk_user}）。店长代建账号等于把任意手机号变成在册员工，
 * 对方再用 ADR-011 的 RESET 流程改密码就是账号接管面——所以未注册一律拒绝并引导先注册。
 *
 * <p>无手机号列可存（V1 的 emp_employee 没有 phone/avatar 列），故本命令也不含它们；
 * 头像列不存在 → 前端一律首字母占位，不放假照片。
 */
@Data
public class OnboardEmployeeCommand {

    @NotBlank(message = "手机号必填")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "姓名必填")
    @Size(max = 50, message = "姓名不超过 50 字")
    private String name;

    @Size(max = 32, message = "工号不超过 32 字")
    private String employeeNo;

    /** 入职日，缺省=今日（由服务层归一，前端不传也不会落 NULL 之外的值） */
    private LocalDate hiredAt;

    /** 本店角色集合，至少一个——录了人却不给角色，此人在本店既看不了任务也接不了指派 */
    @NotEmpty(message = "至少授予一个角色")
    private List<String> roleCodes;
}
