package com.petplatform.employee.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 本店员工名册视图（ADR-024；表 Owner=employee）。
 * 只含 emp_* 四表可得的事实：档案 + **当前工作门店**的角色（emp_employee_role 带 store_id，
 * 同人 A 店 DOCTOR/B 店 GROOMER，故不取全局并集）+ 技能 + 全部门店归属。
 * 手机号与今日排班不在这里——分属 user/schedule，由 app 装配层拼装（ADR-024 §3）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeRosterView {

    private Long employeeId;
    /** 关联账号（usr_user.id，emp_employee.uk_user）；供上层取手机号脱敏，不出前端 */
    private Long userId;
    private String name;
    private String employeeNo;
    /** ACTIVE/ON_LEAVE/SUSPENDED/RESIGNED */
    private String status;
    private LocalDate hiredAt;
    private LocalDate leftAt;
    private List<String> roleCodes;
    /** 后端拼好的角色中文名串（前台只读视图也要显示角色，故不裁剪此字段） */
    private String rolesLabel;
    private List<String> skillCodes;
    private List<StoreAffiliation> affiliations;
    /** ACTIVE 归属门店数（long → 全局 Jackson 字符串化） */
    private long activeStoreCount;
    /**
     * 当前请求门店的归属状态：ACTIVE / ENDED（不含他店信息，故不是 affiliations 的替代）。
     * 由 EXISTS 语义计算——本请求门店存在 ACTIVE 归属行即为 ACTIVE（调店残留的 ENDED 行不影响判定），
     * 否则 ENDED。名册保留历史行（ADR-024：已移出本店者仍在册），故展示层需要一个不被脚注④裁剪的
     * 当前店归属信号；它不是从 activeStoreCount 推断的（那是"总 ACTIVE 店数"，与本店的有无无关）。
     */
    private String currentStoreAffiliation;

    /** 门店归属（emp_employee_store；调店=旧行 ENDED + 新行 ACTIVE，ADR-004）。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StoreAffiliation {
        private Long storeId;
        /** 经 StoreQueryApi 回填；门店已删时为 null，前端回退「门店 {id}」，不编店名 */
        private String storeName;
        /** 用包装类型：Lombok 对 `boolean isPrimary` 生成 isPrimary() getter，Jackson 会退化成 "primary" 字段名 */
        private Boolean isPrimary;
        /** ACTIVE/ENDED */
        private String status;
        private LocalDate startedAt;
        private LocalDate endedAt;
        /** 该店角色（与归属同维度） */
        private List<String> roleCodes;
    }
}
