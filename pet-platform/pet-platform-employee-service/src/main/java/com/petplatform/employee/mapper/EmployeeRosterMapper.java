package com.petplatform.employee.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 本店在职员工名条只读投影（ADR-018；map-underscore-to-camel 自动映射，不建实体）。
 * 角色按 store_id 关联（emp_employee_role 是店维度的），无角色员工仍返回（LEFT JOIN）。
 */
@Mapper
public interface EmployeeRosterMapper {

    @Select("""
            SELECT e.id AS employee_id, e.name AS name,
                   GROUP_CONCAT(DISTINCT r.code ORDER BY r.code SEPARATOR ',') AS role_codes,
                   GROUP_CONCAT(DISTINCT r.name ORDER BY r.name SEPARATOR '、') AS roles_label
            FROM emp_employee e
            JOIN emp_employee_store es ON es.employee_id = e.id AND es.deleted = 0
                 AND es.status = 'ACTIVE' AND es.store_id = #{storeId}
            LEFT JOIN emp_employee_role er ON er.employee_id = e.id AND er.deleted = 0
                 AND er.store_id = #{storeId}
            LEFT JOIN emp_role r ON r.id = er.role_id AND r.deleted = 0
            WHERE e.deleted = 0 AND e.status = 'ACTIVE'
            GROUP BY e.id, e.name
            ORDER BY e.id ASC
            """)
    List<StaffRow> selectStaffInStore(@Param("storeId") Long storeId);

    /** 全部员工账号 user_id（ADR-017 §1 客户列表差集用；含离职，故只按 deleted=0 过滤）。 */
    @Select("SELECT e.user_id FROM emp_employee e WHERE e.deleted = 0 AND e.user_id IS NOT NULL")
    java.util.Set<Long> selectEmployeeUserIds();

    /** 查询投影行类（MyBatis 需要可实例化的普通类，@Builder-only 会 10009）。 */
    class StaffRow {
        private Long employeeId;
        private String name;
        private String roleCodes;
        private String rolesLabel;

        public Long getEmployeeId() { return employeeId; }
        public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getRoleCodes() { return roleCodes; }
        public void setRoleCodes(String roleCodes) { this.roleCodes = roleCodes; }
        public String getRolesLabel() { return rolesLabel; }
        public void setRolesLabel(String rolesLabel) { this.rolesLabel = rolesLabel; }
    }
}
