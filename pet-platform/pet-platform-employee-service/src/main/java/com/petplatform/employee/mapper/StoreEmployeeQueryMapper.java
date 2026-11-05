package com.petplatform.employee.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 本店员工名册只读投影（ADR-024；不建实体，纯读 emp_* 四表，风格同 EmployeeRosterMapper）。
 * **不 join sto_store**：门店名由服务层经 StoreQueryApi 回填，跨模块 SQL join 等于绕过表 Owner。
 * 行类必须是可实例化的普通类（@Builder-only 会让 MyBatis 部分列映射抛 10009）。
 */
@Mapper
public interface StoreEmployeeQueryMapper {

    /** 在该店有过归属的员工 + **本店维度**的角色串（GROUP BY 只到员工，调店双归属行不会把同一人拆两行）。 */
    @Select("""
            SELECT e.id AS employee_id, e.user_id AS user_id, e.name AS name,
                   e.employee_no AS employee_no, e.status AS status, e.hired_at AS hired_at, e.left_at AS left_at,
                   GROUP_CONCAT(DISTINCT r.code ORDER BY r.code SEPARATOR ',') AS role_codes,
                   GROUP_CONCAT(DISTINCT r.name ORDER BY r.name SEPARATOR '、') AS roles_label
            FROM emp_employee e
            JOIN emp_employee_store es ON es.employee_id = e.id AND es.deleted = 0 AND es.store_id = #{storeId}
            LEFT JOIN emp_employee_role er ON er.employee_id = e.id AND er.deleted = 0 AND er.store_id = #{storeId}
            LEFT JOIN emp_role r ON r.id = er.role_id AND r.deleted = 0
            WHERE e.deleted = 0
            GROUP BY e.id, e.user_id, e.name, e.employee_no, e.status, e.hired_at, e.left_at
            ORDER BY e.id ASC
            """)
    List<RosterRow> selectRoster(@Param("storeId") Long storeId);

    /** 批量取员工的**全部门店归属**及其各自角色（跨店角色差异是 ADR-004 的既定语义）。 */
    @Select("""
            <script>
            SELECT es.employee_id AS employee_id, es.store_id AS store_id, es.is_primary AS is_primary,
                   es.status AS status, es.started_at AS started_at, es.ended_at AS ended_at,
                   GROUP_CONCAT(DISTINCT r.code ORDER BY r.code SEPARATOR ',') AS role_codes
            FROM emp_employee_store es
            LEFT JOIN emp_employee_role er ON er.employee_id = es.employee_id AND er.store_id = es.store_id AND er.deleted = 0
            LEFT JOIN emp_role r ON r.id = er.role_id AND r.deleted = 0
            WHERE es.deleted = 0 AND es.employee_id IN
            <foreach collection="employeeIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY es.id, es.employee_id, es.store_id, es.is_primary, es.status, es.started_at, es.ended_at
            ORDER BY es.employee_id ASC, es.store_id ASC
            </script>
            """)
    List<AffiliationRow> selectAffiliations(@Param("employeeIds") Collection<Long> employeeIds);

    @Select("""
            <script>
            SELECT es.employee_id AS employee_id,
                   GROUP_CONCAT(DISTINCT s.code ORDER BY s.code SEPARATOR ',') AS skill_codes
            FROM emp_employee_skill es
            JOIN emp_skill s ON s.id = es.skill_id AND s.deleted = 0
            WHERE es.deleted = 0 AND es.employee_id IN
            <foreach collection="employeeIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY es.employee_id
            </script>
            """)
    List<SkillRow> selectSkills(@Param("employeeIds") Collection<Long> employeeIds);

    class RosterRow {
        private Long employeeId;
        private Long userId;
        private String name;
        private String employeeNo;
        private String status;
        private LocalDate hiredAt;
        private LocalDate leftAt;
        private String roleCodes;
        private String rolesLabel;

        public Long getEmployeeId() { return employeeId; }
        public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getEmployeeNo() { return employeeNo; }
        public void setEmployeeNo(String employeeNo) { this.employeeNo = employeeNo; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public LocalDate getHiredAt() { return hiredAt; }
        public void setHiredAt(LocalDate hiredAt) { this.hiredAt = hiredAt; }
        public LocalDate getLeftAt() { return leftAt; }
        public void setLeftAt(LocalDate leftAt) { this.leftAt = leftAt; }
        public String getRoleCodes() { return roleCodes; }
        public void setRoleCodes(String roleCodes) { this.roleCodes = roleCodes; }
        public String getRolesLabel() { return rolesLabel; }
        public void setRolesLabel(String rolesLabel) { this.rolesLabel = rolesLabel; }
    }

    class AffiliationRow {
        private Long employeeId;
        private Long storeId;
        private Boolean isPrimary;
        private String status;
        private LocalDate startedAt;
        private LocalDate endedAt;
        private String roleCodes;

        public Long getEmployeeId() { return employeeId; }
        public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }
        public Long getStoreId() { return storeId; }
        public void setStoreId(Long storeId) { this.storeId = storeId; }
        public Boolean getIsPrimary() { return isPrimary; }
        public void setIsPrimary(Boolean isPrimary) { this.isPrimary = isPrimary; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public LocalDate getStartedAt() { return startedAt; }
        public void setStartedAt(LocalDate startedAt) { this.startedAt = startedAt; }
        public LocalDate getEndedAt() { return endedAt; }
        public void setEndedAt(LocalDate endedAt) { this.endedAt = endedAt; }
        public String getRoleCodes() { return roleCodes; }
        public void setRoleCodes(String roleCodes) { this.roleCodes = roleCodes; }
    }

    class SkillRow {
        private Long employeeId;
        private String skillCodes;

        public Long getEmployeeId() { return employeeId; }
        public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }
        public String getSkillCodes() { return skillCodes; }
        public void setSkillCodes(String skillCodes) { this.skillCodes = skillCodes; }
    }
}
