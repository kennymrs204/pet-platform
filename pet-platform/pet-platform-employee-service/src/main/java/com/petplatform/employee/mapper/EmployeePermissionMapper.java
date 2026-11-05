package com.petplatform.employee.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 权限码只读投影查询（不建实体，纯读 emp_* 五表）。
 */
@Mapper
public interface EmployeePermissionMapper {

    @Select("""
            SELECT rp.permission_code
            FROM emp_role_permission rp
            JOIN emp_role r ON r.id = rp.role_id AND r.deleted = 0
            WHERE r.code = #{roleCode}
            """)
    List<String> selectCodesByRoleCode(@Param("roleCode") String roleCode);

    /**
     * 员工在指定门店的权限码并集（第八阶段 §1.2 装配链）：
     * emp_employee(ACTIVE) → emp_employee_store(ACTIVE,该店) → emp_employee_role(该店) → emp_role_permission。
     * 非员工/离职/不归属该店时自然返回空集。
     */
    @Select("""
            SELECT DISTINCT rp.permission_code
            FROM emp_employee e
            JOIN emp_employee_store es ON es.employee_id = e.id AND es.deleted = 0
                 AND es.status = 'ACTIVE' AND es.store_id = #{storeId}
            JOIN emp_employee_role er ON er.employee_id = e.id AND er.deleted = 0
                 AND er.store_id = #{storeId}
            JOIN emp_role_permission rp ON rp.role_id = er.role_id
            JOIN emp_role r ON r.id = rp.role_id AND r.deleted = 0
            WHERE e.user_id = #{userId} AND e.status = 'ACTIVE' AND e.deleted = 0
            """)
    List<String> selectStorePermissions(@Param("userId") Long userId, @Param("storeId") Long storeId);

    @Select("""
            SELECT e.id
            FROM emp_employee e
            JOIN emp_employee_store es ON es.employee_id = e.id AND es.deleted = 0
                 AND es.status = 'ACTIVE' AND es.store_id = #{storeId}
            WHERE e.user_id = #{userId} AND e.status = 'ACTIVE' AND e.deleted = 0
            LIMIT 1
            """)
    Long selectEmployeeIdInStore(@Param("userId") Long userId, @Param("storeId") Long storeId);

    /**
     * 任意 ACTIVE 归属下持有该角色者返回 1（ADR-044 平台管理员识别）。
     * 与 selectStorePermissions 同一条链，只是去掉 {@code es.store_id} 过滤——
     * 保留 es JOIN 是为了"离职/归属全结束的人不算平台管理员"这条语义不被削弱。
     */
    @Select("""
            SELECT COUNT(*) FROM emp_employee e
            JOIN emp_employee_store es ON es.employee_id = e.id AND es.deleted = 0
                 AND es.status = 'ACTIVE'
            JOIN emp_employee_role er ON er.employee_id = e.id AND er.deleted = 0
            JOIN emp_role r ON r.id = er.role_id AND r.deleted = 0
            WHERE e.user_id = #{userId} AND e.status = 'ACTIVE' AND e.deleted = 0
              AND r.code = #{roleCode}
            """)
    int selectGlobalRoleHeld(@Param("userId") Long userId, @Param("roleCode") String roleCode);

    /**
     * 反向：哪些角色持有该权限码（ADR-047 收件人判据）。与 {@code selectCodesByRoleCode} 是同一条 JOIN
     * 的两个方向，所以"角色有什么码"与"什么角色有这个码"不可能对不上。
     */
    @Select("""
            SELECT DISTINCT r.code
            FROM emp_role_permission rp
            JOIN emp_role r ON r.id = rp.role_id AND r.deleted = 0
            WHERE rp.permission_code = #{permissionCode}
            """)
    List<String> selectRoleCodesHolding(@Param("permissionCode") String permissionCode);

    /**
     * 持有该角色的在职员工 userId（ADR-047 平台管理员 fan-out）。
     * JOIN 链逐条照抄 {@code selectGlobalRoleHeld}：两处判断"谁算持有者"必须同构，否则一边发一边不发。
     */
    @Select("""
            SELECT DISTINCT e.user_id
            FROM emp_employee e
            JOIN emp_employee_store es ON es.employee_id = e.id AND es.deleted = 0
                 AND es.status = 'ACTIVE'
            JOIN emp_employee_role er ON er.employee_id = e.id AND er.deleted = 0
            JOIN emp_role r ON r.id = er.role_id AND r.deleted = 0
            WHERE e.status = 'ACTIVE' AND e.deleted = 0
              AND r.code = #{roleCode} AND e.user_id IS NOT NULL
            ORDER BY e.user_id ASC
            """)
    List<Long> selectUserIdsWithRole(@Param("roleCode") String roleCode);

    /** 员工 id → 登录主体 user_id（仅 ACTIVE 员工；通知桥 ADR-052 用，查无者静默缺席）。 */
    @Select("""
            <script>
            SELECT e.id, e.user_id
            FROM emp_employee e
            WHERE e.id IN
            <foreach collection="employeeIds" item="eid" open="(" separator="," close=")">#{eid}</foreach>
              AND e.status = 'ACTIVE' AND e.deleted = 0
            </script>
            """)
    List<EmployeeUserIdRow> selectUserIdsByEmployeeIds(@Param("employeeIds") Collection<Long> employeeIds);

    class EmployeeUserIdRow {
        private Long id;
        private Long userId;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
    }

    /** ACTIVE 员工抬头（uk_user 保证至多一行）。 */
    @Select("""
            SELECT e.id, e.name, e.status
            FROM emp_employee e
            WHERE e.user_id = #{userId} AND e.status = 'ACTIVE' AND e.deleted = 0
            LIMIT 1
            """)
    EmployeeHeaderRow selectActiveEmployeeHeader(@Param("userId") Long userId);

    /**
     * 员工全部 ACTIVE 门店归属 × 角色扁平行（ADR-012）。
     * LEFT JOIN 角色：无角色绑定的 ACTIVE 归属仍返回（roleCode 为 null）。
     * 排序即契约：主门店优先 → store_id 升序 → 角色码升序。
     */
    @Select("""
            SELECT es.store_id, es.is_primary, r.code AS role_code
            FROM emp_employee_store es
            LEFT JOIN emp_employee_role er ON er.employee_id = es.employee_id AND er.deleted = 0
                 AND er.store_id = es.store_id
            LEFT JOIN emp_role r ON r.id = er.role_id AND r.deleted = 0
            WHERE es.employee_id = #{employeeId} AND es.deleted = 0 AND es.status = 'ACTIVE'
            ORDER BY es.is_primary DESC, es.store_id ASC, r.code ASC
            """)
    List<StoreRoleRow> selectActiveStoreRoles(@Param("employeeId") Long employeeId);

    /** 只读投影（map-underscore-to-camel 自动映射，不建实体）。 */
    /**
     * 员工批量简要：姓名 + 在指定门店的角色码（LEFT JOIN，无该店角色仍返回姓名行）。
     */
    @Select("""
            <script>
            SELECT e.id AS employee_id, e.name, r.code AS role_code
            FROM emp_employee e
            LEFT JOIN emp_employee_role er ON er.employee_id = e.id AND er.deleted = 0
                 AND er.store_id = #{storeId}
            LEFT JOIN emp_role r ON r.id = er.role_id AND r.deleted = 0
            WHERE e.deleted = 0 AND e.id IN
            <foreach collection="employeeIds" item="eid" open="(" separator="," close=")">#{eid}</foreach>
            ORDER BY e.id ASC, r.code ASC
            </script>
            """)
    List<EmployeeBriefRow> selectBriefsInStore(@Param("employeeIds") java.util.Collection<Long> employeeIds,
                                               @Param("storeId") Long storeId);

    /** 在职 ∧ 该店 ACTIVE 归属（ADR-015 护理员校验）。 */
    @Select("""
            SELECT COUNT(*) FROM emp_employee e
            JOIN emp_employee_store es ON es.employee_id = e.id AND es.deleted = 0
                 AND es.status = 'ACTIVE' AND es.store_id = #{storeId}
            WHERE e.deleted = 0 AND e.status = 'ACTIVE' AND e.id = #{employeeId}
            """)
    long countActiveInStore(@Param("employeeId") Long employeeId, @Param("storeId") Long storeId);

    /** 员工技能 id（ADR-016 可约性条4）。 */
    @Select("SELECT es.skill_id FROM emp_employee_skill es"
            + " WHERE es.deleted = 0 AND es.employee_id = #{employeeId}")
    java.util.List<Long> selectSkillIds(@Param("employeeId") Long employeeId);

    class EmployeeBriefRow {
        private Long employeeId;
        private String name;
        private String roleCode;

        public Long getEmployeeId() { return employeeId; }
        public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getRoleCode() { return roleCode; }
        public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    }

    class EmployeeHeaderRow {
        private Long id;
        private String name;
        private String status;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    class StoreRoleRow {
        private Long storeId;
        private Integer isPrimary;
        private String roleCode;

        public Long getStoreId() { return storeId; }
        public void setStoreId(Long storeId) { this.storeId = storeId; }
        public Integer getIsPrimary() { return isPrimary; }
        public void setIsPrimary(Integer isPrimary) { this.isPrimary = isPrimary; }
        public String getRoleCode() { return roleCode; }
        public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    }
}
