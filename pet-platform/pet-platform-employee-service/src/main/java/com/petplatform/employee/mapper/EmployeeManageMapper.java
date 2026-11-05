package com.petplatform.employee.mapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 员工写侧 SQL（ADR-033）。全部是 {@code emp_*} 自家表——表 Owner 边界内，零跨模块 JOIN。
 *
 * <p>逻辑删除一律写 {@code deleted = id}（BaseEntity 的既定口径：delval=id，
 * 这样 {@code uk_emp_role_store(employee_id, role_id, store_id, deleted)} 与
 * {@code uk_emp_skill} 才能容纳"授→撤→再授"的历史行而不撞键）。
 */
@Mapper
public interface EmployeeManageMapper {

    /** 员工档案行（按登录主体查）。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class EmployeeRow {
        private Long id;
        private Long userId;
        private String name;
        private String employeeNo;
        private String status;
    }

    /** 门店归属行。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class AffiliationRow {
        private Long id;
        private Integer isPrimary;
        private String status;
    }

    /** 字典行（角色或技能：id + code）。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class CodeRow {
        private Long id;
        private String code;
    }

    @Select("SELECT id, user_id, name, employee_no, status FROM emp_employee WHERE user_id = #{userId} AND deleted = 0 LIMIT 1")
    EmployeeRow selectEmployeeByUser(@Param("userId") Long userId);

    @Select("SELECT id, user_id, name, employee_no, status FROM emp_employee WHERE id = #{employeeId} AND deleted = 0 LIMIT 1")
    EmployeeRow selectEmployeeById(@Param("employeeId") Long employeeId);

    @Insert("INSERT INTO emp_employee (id, user_id, employee_no, name, status, hired_at) "
            + "VALUES (#{id}, #{userId}, #{employeeNo}, #{name}, 'ACTIVE', #{hiredAt})")
    int insertEmployee(@Param("id") Long id, @Param("userId") Long userId, @Param("employeeNo") String employeeNo,
                       @Param("name") String name, @Param("hiredAt") LocalDate hiredAt);

    /** 平台侧编辑全局档案（ADR-050）：三列全量提交，employeeNo/hiredAt 传 null=清空（两列本就可空）。 */
    @Update("UPDATE emp_employee SET name = #{name}, employee_no = #{employeeNo}, hired_at = #{hiredAt} "
            + "WHERE id = #{employeeId} AND deleted = 0")
    int updateProfile(@Param("employeeId") Long employeeId, @Param("employeeNo") String employeeNo,
                      @Param("name") String name, @Param("hiredAt") LocalDate hiredAt);

    @Select("SELECT id, is_primary, status FROM emp_employee_store "
            + "WHERE employee_id = #{employeeId} AND store_id = #{storeId} AND deleted = 0 LIMIT 1")
    AffiliationRow selectAffiliation(@Param("employeeId") Long employeeId, @Param("storeId") Long storeId);

    @Insert("INSERT INTO emp_employee_store (id, employee_id, store_id, is_primary, status, started_at) "
            + "VALUES (#{id}, #{employeeId}, #{storeId}, #{isPrimary}, 'ACTIVE', #{startedAt})")
    int insertAffiliation(@Param("id") Long id, @Param("employeeId") Long employeeId, @Param("storeId") Long storeId,
                          @Param("isPrimary") int isPrimary, @Param("startedAt") LocalDate startedAt);

    /** 复活既有归属（uk_emp_store 决定了同一(人,店)只能一行，调店回同店不能插新行只能复活）。 */
    @Update("UPDATE emp_employee_store SET status = 'ACTIVE', ended_at = NULL, started_at = #{startedAt} "
            + "WHERE employee_id = #{employeeId} AND store_id = #{storeId} AND deleted = 0")
    int reviveAffiliation(@Param("employeeId") Long employeeId, @Param("storeId") Long storeId,
                          @Param("startedAt") LocalDate startedAt);

    @Update("UPDATE emp_employee_store SET status = 'ENDED', ended_at = CURDATE() "
            + "WHERE employee_id = #{employeeId} AND store_id = #{storeId} AND deleted = 0 AND status = 'ACTIVE'")
    int endAffiliation(@Param("employeeId") Long employeeId, @Param("storeId") Long storeId);

    /** 该员工当前的主店（ACTIVE 归属里 is_primary=1 的那家）。 */
    @Select("SELECT store_id FROM emp_employee_store WHERE employee_id = #{employeeId} AND deleted = 0 "
            + "AND status = 'ACTIVE' AND is_primary = 1 LIMIT 1")
    Long selectPrimaryStore(@Param("employeeId") Long employeeId);

    /** 主店转移候选：除本店外最小编号的 ACTIVE 归属（确定性，不随查询顺序漂移）。 */
    @Select("SELECT store_id FROM emp_employee_store WHERE employee_id = #{employeeId} AND deleted = 0 "
            + "AND status = 'ACTIVE' AND store_id <> #{releasedStoreId} ORDER BY store_id ASC LIMIT 1")
    Long selectNextActiveStore(@Param("employeeId") Long employeeId, @Param("releasedStoreId") Long releasedStoreId);

    @Update("UPDATE emp_employee_store SET is_primary = #{isPrimary} "
            + "WHERE employee_id = #{employeeId} AND store_id = #{storeId} AND deleted = 0")
    int markPrimary(@Param("employeeId") Long employeeId, @Param("storeId") Long storeId, @Param("isPrimary") int isPrimary);

    /** 角色字典：只回未删的，scope 一并带出（PLATFORM 维度的 CUSTOMER/ADMIN 由服务层按白名单挡，不在 SQL 里猜）。 */
    @Select("<script>SELECT id, code FROM emp_role WHERE deleted = 0 AND code IN"
            + " <foreach collection=\"codes\" item=\"c\" open=\"(\" separator=\",\" close=\")\">#{c}</foreach>"
            + " ORDER BY code ASC</script>")
    List<CodeRow> selectRolesByCodes(@Param("codes") Collection<String> codes);

    /** 技能字典（emp_skill 同模块自家表）。 */
    @Select("<script>SELECT id, code FROM emp_skill WHERE deleted = 0 AND code IN"
            + " <foreach collection=\"codes\" item=\"c\" open=\"(\" separator=\",\" close=\")\">#{c}</foreach>"
            + " ORDER BY code ASC</script>")
    List<CodeRow> selectSkillsByCodes(@Param("codes") Collection<String> codes);

    @Select("SELECT r.code FROM emp_employee_role er JOIN emp_role r ON r.id = er.role_id AND r.deleted = 0 "
            + "WHERE er.deleted = 0 AND er.employee_id = #{employeeId} AND er.store_id = #{storeId} ORDER BY r.code ASC")
    List<String> selectRoleCodes(@Param("employeeId") Long employeeId, @Param("storeId") Long storeId);

    @Select("SELECT s.code FROM emp_employee_skill es JOIN emp_skill s ON s.id = es.skill_id AND s.deleted = 0 "
            + "WHERE es.deleted = 0 AND es.employee_id = #{employeeId} ORDER BY s.code ASC")
    List<String> selectSkillCodes(@Param("employeeId") Long employeeId);

    /** 全量替换的第一步：逻辑删掉该员工在本店的全部角色行。 */
    @Update("UPDATE emp_employee_role SET deleted = id "
            + "WHERE employee_id = #{employeeId} AND store_id = #{storeId} AND deleted = 0")
    int softDeleteRoles(@Param("employeeId") Long employeeId, @Param("storeId") Long storeId);

    /**
     * 本店除 {@code excludeEmployeeId} 之外还有几名**能干活**的店长（ADR-035 守卫的唯一判据）。
     *
     * <p>四表 JOIN 刻意与 {@code EmployeePermissionMapper.storePermissions} 同构（在职 ∧ 该店 ACTIVE 归属 ∧
     * 该店挂着 STORE_MANAGER），因为守卫要回答的问题是"这家店还剩谁能调 /store/employees"，
     * 而不是"名义上还有谁被授过店长"——停用的、归属已结束的店长救不了这家店，不能算进去。
     */
    @Select("SELECT COUNT(*) FROM emp_employee e "
            + "JOIN emp_employee_store es ON es.employee_id = e.id AND es.store_id = #{storeId} "
            + "  AND es.status = 'ACTIVE' AND es.deleted = 0 "
            + "JOIN emp_employee_role er ON er.employee_id = e.id AND er.store_id = #{storeId} AND er.deleted = 0 "
            + "JOIN emp_role r ON r.id = er.role_id AND r.code = 'STORE_MANAGER' AND r.deleted = 0 "
            + "WHERE e.status = 'ACTIVE' AND e.deleted = 0 AND e.id <> #{excludeEmployeeId}")
    long countOtherActiveManagers(@Param("storeId") Long storeId, @Param("excludeEmployeeId") Long excludeEmployeeId);

    @Insert("INSERT INTO emp_employee_role (id, employee_id, role_id, store_id) "
            + "VALUES (#{id}, #{employeeId}, #{roleId}, #{storeId})")
    int insertRole(@Param("id") Long id, @Param("employeeId") Long employeeId, @Param("roleId") Long roleId,
                   @Param("storeId") Long storeId);

    @Update("UPDATE emp_employee_skill SET deleted = id WHERE employee_id = #{employeeId} AND deleted = 0")
    int softDeleteSkills(@Param("employeeId") Long employeeId);

    @Insert("INSERT INTO emp_employee_skill (id, employee_id, skill_id) VALUES (#{id}, #{employeeId}, #{skillId})")
    int insertSkill(@Param("id") Long id, @Param("employeeId") Long employeeId, @Param("skillId") Long skillId);

    /**
     * ADR-055 全局状态守卫的判据：该员工当前在哪些店是"能干活的店长"（在职 ∧ 该店 ACTIVE 归属 ∧ 挂 STORE_MANAGER）。
     * JOIN 链与 {@link #countOtherActiveManagers} 同构——两处判断"谁算店长"必须一致，否则守卫会漏店或误拦。
     */
    @Select("SELECT DISTINCT es.store_id FROM emp_employee e "
            + "JOIN emp_employee_store es ON es.employee_id = e.id AND es.status = 'ACTIVE' AND es.deleted = 0 "
            + "JOIN emp_employee_role er ON er.employee_id = e.id AND er.store_id = es.store_id AND er.deleted = 0 "
            + "JOIN emp_role r ON r.id = er.role_id AND r.code = 'STORE_MANAGER' AND r.deleted = 0 "
            + "WHERE e.id = #{employeeId} AND e.status = 'ACTIVE' AND e.deleted = 0 "
            + "ORDER BY es.store_id ASC")
    List<Long> selectActiveManagerStores(@Param("employeeId") Long employeeId);

    /** 全局状态写（ADR-055）：三值白名单在服务层；0 行=并发软删，按不存在处理。 */
    @Update("UPDATE emp_employee SET status = #{status} WHERE id = #{employeeId} AND deleted = 0")
    int updateStatus(@Param("employeeId") Long employeeId, @Param("status") String status);
}
