package com.petplatform.employee.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.employee.api.EmployeeManageApi;
import com.petplatform.employee.api.dto.EmployeeManageView;
import com.petplatform.employee.api.dto.EmployeeStatusUpdateCommand;
import com.petplatform.employee.api.dto.OnboardEmployeeCommand;
import com.petplatform.employee.mapper.EmployeeManageMapper;
import com.petplatform.user.api.UserAccountApi;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 员工写侧（ADR-033，兑现 ADR-001-E）。全部落在 {@code emp_*} 自家表，跨域只经 {@code user-api} 查一个 id
 * （矩阵 employee→user ✓，employee→store ✓）。
 *
 * <p>三条贯穿全局的口径：
 * 1) **门店上下文即授权**：每个方法都先要"该员工在本店有 ACTIVE 归属"，否则 10004（跨店与不存在同码，防探测）；
 * 2) **不碰全局字段**：姓名/工号/员工状态是跨店共享的一行，店长端点只在缺失时写入，绝不覆盖他店已建的档案；
 * 3) **授/撤 = 全量替换**：先按 (员工,本店) 逻辑删干净再插，语义与 ADR-010/027 的 PUT 全量同族，
 *    天然幂等（重复提交同一集合结果一致），也避免"增量 diff"在并发下互相吞掉变更。
 */
@Service
public class EmployeeManageService implements EmployeeManageApi {

    private static final String STORE_MANAGER = "STORE_MANAGER";

    /** 全局状态白名单（ADR-055）；DDL 注释里的 ON_LEAVE 全仓无消费方，不纳入。 */
    private static final Set<String> GLOBAL_STATUSES = Set.of("ACTIVE", "SUSPENDED", "RESIGNED");

    private final EmployeeManageMapper mapper;
    private final UserAccountApi userAccountApi;

    public EmployeeManageService(EmployeeManageMapper mapper, UserAccountApi userAccountApi) {
        this.mapper = mapper;
        this.userAccountApi = userAccountApi;
    }

    @Override
    @Transactional
    public EmployeeManageView onboard(Long storeId, OnboardEmployeeCommand cmd) {
        Long userId = userAccountApi.userIdByPhone(cmd.getPhone());
        if (userId == null) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND,
                    "该手机号尚未注册账号，请先让对方在 B 端完成注册（需短信验证码）");
        }
        List<String> wanted = distinctNonBlank(cmd.getRoleCodes());
        if (wanted.isEmpty()) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "至少授予一个角色");
        }
        List<EmployeeManageMapper.CodeRow> roles = resolveRoles(wanted, STORE_ASSIGNABLE_ROLES);
        LocalDate today = com.petplatform.common.time.BusinessDates.today();

        EmployeeManageMapper.EmployeeRow existing = mapper.selectEmployeeByUser(userId);
        if (existing != null && "RESIGNED".equals(existing.getStatus())) {
            // 全局离职是平台侧动作；店长纳编不擅自把别人停掉的人复活
            throw new BusinessException(CommonErrorCode.PARAM_INVALID,
                    "该账号已是离职员工，请由平台管理员恢复后再纳编");
        }
        if (existing != null && "SUSPENDED".equals(existing.getStatus())) {
            // ADR-055 补一致：停用者纳编若不拦，接口"成功"但人进不了任何名单（STORE ACTIVE 闸）——对外撒谎
            throw new BusinessException(CommonErrorCode.PARAM_INVALID,
                    "该账号已被停用，请由平台管理员恢复后再纳编");
        }
        boolean employeeCreated = existing == null;
        EmployeeManageMapper.EmployeeRow emp = employeeCreated ? newProfile(userId, cmd, today) : existing;
        Long employeeId = emp.getId();

        EmployeeManageMapper.AffiliationRow aff = mapper.selectAffiliation(employeeId, storeId);
        boolean affiliationCreated = aff == null || !"ACTIVE".equals(aff.getStatus());
        if (aff == null) {
            Long eid = employeeId;
            guard(() -> mapper.insertAffiliation(IdWorker.getId(), eid, storeId, 0, today));
        } else if (!"ACTIVE".equals(aff.getStatus())) {
            // uk_emp_store 决定同一(人,店)只能一行 → 回店只能复活原行，不能插新行
            guard(() -> mapper.reviveAffiliation(employeeId, storeId, today));
        }
        // 无任何 ACTIVE 主店时把本店补成主店（否则 /auth/employee-identities 的排序退化成任意序）
        if (mapper.selectPrimaryStore(employeeId) == null) {
            mapper.markPrimary(employeeId, storeId, 1);
        }
        // ADR-035：纳编走的也是按(员工,本店)全量替换，所以"把一名在职店长当新人纳编、只勾 GROOMER"
        // 会静默摘掉他的店长身份——这条路径必须和显式撤角色走同一个守卫。
        assertNotLastManager(storeId, employeeId, roles);
        // 门店端白名单不含 STORE_MANAGER：目标在本店仍是店长时整单拒绝（含"还有另一名店长"的情形）。
        assertMayReplaceStoreManager(storeId, employeeId, STORE_ASSIGNABLE_ROLES);
        guard(() -> applyRoles(employeeId, storeId, roles));
        return view(emp, storeId, employeeCreated, affiliationCreated);
    }

    /** 新建全局员工档案（姓名/工号写一次；此后跨店共享，任何一店都改不动另一店建的档）。 */
    private EmployeeManageMapper.EmployeeRow newProfile(Long userId, OnboardEmployeeCommand cmd, LocalDate today) {
        long id = IdWorker.getId();
        String name = cmd.getName().trim();
        String no = emptyToNull(cmd.getEmployeeNo());
        LocalDate hired = cmd.getHiredAt() == null ? today : cmd.getHiredAt();
        guard(() -> mapper.insertEmployee(id, userId, no, name, hired));
        return new EmployeeManageMapper.EmployeeRow(id, userId, name, no, "ACTIVE");
    }

    @Override
    @Transactional
    public EmployeeManageView replaceRoles(Long storeId, Long employeeId, List<String> roleCodes,
                                          Set<String> callerRoles) {
        EmployeeManageMapper.EmployeeRow emp = requireVisible(storeId, employeeId);
        List<EmployeeManageMapper.CodeRow> roles = resolveRoles(distinctNonBlank(roleCodes), callerRoles);
        assertNotLastManager(storeId, employeeId, roles);
        // 全删重插前最后一道闸：门店入口不得触碰本店店长身份（哪怕本次提交里没写 STORE_MANAGER）。
        assertMayReplaceStoreManager(storeId, employeeId, callerRoles);
        guard(() -> applyRoles(employeeId, storeId, roles));
        return view(emp, storeId, false, false);
    }

    @Override
    @Transactional
    public EmployeeManageView replaceSkills(Long storeId, Long employeeId, List<String> skillCodes) {
        EmployeeManageMapper.EmployeeRow emp = requireVisible(storeId, employeeId);
        // ADR-040：技能是员工**全局**属性（emp_employee_skill 无 store_id，可约候选也按全局技能判超集），
        // 所以写权必须单一所有者——否则 A 店店长的一次全量替换会清掉 B 店给同一人配的技能，
        // 直接削掉 B 店的接单资格。这与 ADR-033 §2 拒绝店长改姓名/工号是同一条理由，当时只漏了技能。
        assertPrimaryStoreOwnsSkills(storeId, employeeId);
        return doReplaceSkills(emp, storeId, skillCodes);
    }

    /** 技能替换的实现核心：字典校验 → 软删重插 → view（语境由调用方给定）。 */
    private EmployeeManageView doReplaceSkills(EmployeeManageMapper.EmployeeRow emp, Long storeId, List<String> skillCodes) {
        List<String> wanted = distinctNonBlank(skillCodes);
        // 空集合（清空全部技能）不能去查字典：selectSkillsByCodes 的 foreach 会渲染出悬空的 `code IN`，
        // 直接 SQL 语法错（存量缺陷，空数组清空从未真正可用——ADR-051 验收首跑暴露）
        List<EmployeeManageMapper.CodeRow> skills = wanted.isEmpty()
                ? List.of()
                : mapper.selectSkillsByCodes(wanted);
        if (!wanted.isEmpty() && skills.size() != wanted.size()) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "包含未知技能编码");
        }
        guard(() -> {
            mapper.softDeleteSkills(emp.getId());
            skills.forEach(s -> mapper.insertSkill(IdWorker.getId(), emp.getId(), s.getId()));
        });
        return view(emp, storeId, false, false);
    }

    /**
     * 平台侧调整技能（ADR-051）。授权在控制器层（store:manage），服务层语义：
     * 主店非空 → 以主店为语境走 {@link #replaceSkills}（ADR-040 守卫对主店自己恒放行）；
     * 主店为空 → fail-open 直接替换（ADR-040 §3 的口径延伸到平台通道：主店悬空时平台是唯一能写的人）。
     * 两种路都不看"当前代入的是哪家店"——技能写权跟着主店走，不跟着屏幕上的门店走。
     */
    @Override
    @Transactional
    public EmployeeManageView adminReplaceSkills(Long employeeId, List<String> skillCodes) {
        if (employeeId == null) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        EmployeeManageMapper.EmployeeRow emp = mapper.selectEmployeeById(employeeId);
        if (emp == null) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        Long primary = mapper.selectPrimaryStore(employeeId);
        if (primary != null) {
            return replaceSkills(primary, employeeId, skillCodes);
        }
        return doReplaceSkills(emp, null, skillCodes);
    }

    @Override
    @Transactional
    public EmployeeManageView endAffiliation(Long storeId, Long employeeId) {
        EmployeeManageMapper.EmployeeRow emp = requireVisible(storeId, employeeId);
        // 移出的结果是该员工在本店角色清零，故走同一个守卫。这条尤其致命：归属一旦 ENDED，
        // ADMIN 的 /admin/employees/{id}/roles 会因 requireVisible 直接 10004，全系统再无端点能复活归属。
        assertNotLastManager(storeId, employeeId, List.of());
        EmployeeManageMapper.AffiliationRow aff = mapper.selectAffiliation(employeeId, storeId);
        mapper.endAffiliation(employeeId, storeId);
        mapper.markPrimary(employeeId, storeId, 0);
        // 归属一结束，本店角色行必须一起撤销：留着就是"无归属却挂着角色"的脏数据。
        // 权限装配(storePermissions 四表 JOIN)本就要求 ACTIVE 归属，所以不会漏权，但数据面必须自洽。
        mapper.softDeleteRoles(employeeId, storeId);
        // 释放掉的是主店 → 确定性转移给编号最小的其他 ACTIVE 归属；没有则整个员工暂无主店（前端按最小 storeId 回退）
        if (aff != null && Integer.valueOf(1).equals(aff.getIsPrimary())) {
            Long next = mapper.selectNextActiveStore(employeeId, storeId);
            if (next != null) {
                mapper.markPrimary(employeeId, next, 1);
            }
        }
        return view(emp, storeId, false, false);
    }

    /**
     * 平台侧编辑全局档案（ADR-050）。授权在控制器层（{@code store:manage}，实测仅 ADMIN 持有），
     * 服务层只对"这行员工存不存在"负责——**刻意不做任何门店归属要求**：档案是全局的，
     * 已离职（无 ACTIVE 归属）或从未入职者的姓名/工号同样只有平台能改。
     */
    @Override
    @Transactional
    public EmployeeManageView updateProfile(Long employeeId, com.petplatform.employee.api.dto.EmployeeProfileUpdateCommand cmd) {
        if (employeeId == null) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        String name = cmd.getName() == null ? "" : cmd.getName().trim();
        if (name.isEmpty()) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "姓名必填");
        }
        EmployeeManageMapper.EmployeeRow emp = mapper.selectEmployeeById(employeeId);
        if (emp == null) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        String no = emptyToNull(cmd.getEmployeeNo());
        if (mapper.updateProfile(employeeId, no, name, cmd.getHiredAt()) == 0) {
            // 选中与更新之间被并发软删：0 行更新按不存在处理，不假装成功
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        EmployeeManageMapper.EmployeeRow updated = mapper.selectEmployeeById(employeeId);
        // 全局编辑没有门店语境：view 回主店（无主店则 null），角色/技能列照实返回
        return view(updated, mapper.selectPrimaryStore(employeeId), false, false);
    }

    /**
     * 平台侧全局状态（ADR-055）：ACTIVE/SUSPENDED/RESIGNED 三值白名单；同值重复提交幂等（不写库）。
     *
     * <p>离开 ACTIVE 前跑守卫：对"该员工当前挂 STORE_MANAGER 的每个店"，操作后必须仍剩至少一名
     * 能干活的店长——复用 {@link EmployeeManageMapper#countOtherActiveManagers}（四表 JOIN 与
     * storePermissions 同构），否则一个人的停用会把某家店变成"再也没人能恢复编制"的死店
     * （ADR-035 的同一条不变量，判据是店里剩谁，不是自己在不在）。
     *
     * <p>缓存失效与会话撤销刻意不在这里：装配点在 app、撤销端口在 user-api，控制器层事务提交后编排。
     */
    @Override
    @Transactional
    public EmployeeManageView updateStatus(Long employeeId, EmployeeStatusUpdateCommand cmd) {
        if (employeeId == null) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        EmployeeManageMapper.EmployeeRow emp = mapper.selectEmployeeById(employeeId);
        if (emp == null) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        String target = cmd == null || cmd.getStatus() == null ? "" : cmd.getStatus().trim();
        if (!GLOBAL_STATUSES.contains(target)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "状态取值非法（ACTIVE/SUSPENDED/RESIGNED）");
        }
        if (!target.equals(emp.getStatus())) {
            if (!"ACTIVE".equals(target)) {
                assertNotLastManagerEverywhere(employeeId);
            }
            if (mapper.updateStatus(employeeId, target) == 0) {
                // 选中与更新之间被并发软删：0 行按不存在处理，不假装成功
                throw new BusinessException(CommonErrorCode.NOT_FOUND);
            }
            emp = mapper.selectEmployeeById(employeeId);
        }
        return view(emp, mapper.selectPrimaryStore(employeeId), false, false);
    }

    /** ADR-055：只对"他现在还算数的店长店"跑不变量；已被停/已无 ACTIVE 归属时列表自然为空。 */
    private void assertNotLastManagerEverywhere(Long employeeId) {
        for (Long storeId : mapper.selectActiveManagerStores(employeeId)) {
            if (mapper.countOtherActiveManagers(storeId, employeeId) == 0) {
                throw new BusinessException(CommonErrorCode.FORBIDDEN,
                        "该员工是门店 " + storeId + " 的最后一名店长，请先任命他人为店长再停用/离职");
            }
        }
    }

    /* ---------- 内部 ---------- */

    /** 该员工在本店可见可管理吗：必须是 ACTIVE 归属，否则 10004（跨店同码防探测）。 */
    private EmployeeManageMapper.EmployeeRow requireVisible(Long storeId, Long employeeId) {
        EmployeeManageMapper.AffiliationRow aff = employeeId == null ? null : mapper.selectAffiliation(employeeId, storeId);
        if (aff == null || !"ACTIVE".equals(aff.getStatus())) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        EmployeeManageMapper.EmployeeRow emp = mapper.selectEmployeeById(employeeId);
        if (emp == null) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        return emp;
    }

    /**
     * ADR-035：任何一次写操作之后，本店必须仍剩至少一名能干活的店长（在职 ∧ ACTIVE 归属 ∧ 挂 STORE_MANAGER）。
     *
     * <p>刻意不判"是否对自己操作"：两名店长的店里店长撤掉自己完全无害（另一人救得回来），拦它是错误的抽象；
     * 真正致命的是这家店再没人能恢复员工编制。结果集里仍含店长就早退，所以"给零店长的店任命第一位店长"
     * 这条出路永不会被本方法堵死。
     */
    private void assertNotLastManager(Long storeId, Long employeeId,
                                      List<EmployeeManageMapper.CodeRow> resultingRoles) {
        boolean keepsManager = resultingRoles.stream().anyMatch(r -> STORE_MANAGER.equals(r.getCode()));
        if (keepsManager || !mapper.selectRoleCodes(employeeId, storeId).contains(STORE_MANAGER)) {
            return;
        }
        if (mapper.countOtherActiveManagers(storeId, employeeId) > 0) {
            return;
        }
        throw new BusinessException(CommonErrorCode.FORBIDDEN, "这是本店最后一名店长，请先任命他人为店长再撤销");
    }

    /**
     * ADR-001-E 服务端收口（Bug Fix 1）：{@code STORE_MANAGER} 只能由能管理它的入口授予/撤销。
     *
     * <p>本服务的角色写是**按 (员工,本店) 全删重插**：门店端即使提交集合里不含 {@code STORE_MANAGER}，
     * 整体替换仍会把目标原有的店长身份删掉——所以"只校验提交码"挡不住，{@code assertNotLastManager}
     * 也只挡最后一名。这里在**任何软删/写入之前**补齐：调用入口白名单（由服务端控制器按端点给定，
     * 不取自客户端）不含 {@code STORE_MANAGER}、且目标**在本店**当前持有它 → 整单 10003，零部分更新。
     *
     * <p>只看本店行（{@code selectRoleCodes} 带 store_id）：他店店长身份、以及切换门店都不影响本店判定；
     * 平台管理员入口的白名单含 {@code STORE_MANAGER}，故 ADMIN 经既有 /admin 端点授/撤照旧放行。
     */
    private void assertMayReplaceStoreManager(Long storeId, Long employeeId, Set<String> callerRoles) {
        if (callerRoles.contains(STORE_MANAGER)) {
            return;
        }
        if (mapper.selectRoleCodes(employeeId, storeId).contains(STORE_MANAGER)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN,
                    "该员工在本店担任店长，只有平台管理员可以调整其门店角色");
        }
    }

    /**
     * ADR-040：技能只有主店店长可写。主店为空时放行——那是 `is_primary` 从未置位的历史数据，
     * 收紧会让这批人的技能永久改不动，而漂移问题已由 ADR-033 §4 的"移出时确定性转移主店"兜住：
     * 主店店长被移出本店，主店就转到他仍在职的那家店，写权跟着走，不会悬空。
     */
    private void assertPrimaryStoreOwnsSkills(Long storeId, Long employeeId) {
        Long primary = mapper.selectPrimaryStore(employeeId);
        if (primary != null && !primary.equals(storeId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN,
                    "技能是该员工的跨店全局属性，只能由其主店的店长调整（本店可正常授予/撤销本店角色）");
        }
    }

    /** 角色编码 → 字典行，并按调用方白名单把关（白名单外：STORE_MANAGER=10003 越权，其余=10001 参数）。 */
    private List<EmployeeManageMapper.CodeRow> resolveRoles(List<String> codes, Set<String> callerRoles) {
        if (codes.isEmpty()) {
            return List.of();
        }
        List<EmployeeManageMapper.CodeRow> found = mapper.selectRolesByCodes(codes);
        if (found.size() != codes.size()) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "包含未知角色编码");
        }
        for (EmployeeManageMapper.CodeRow r : found) {
            if (callerRoles.contains(r.getCode())) {
                continue;
            }
            if (STORE_MANAGER.equals(r.getCode()) || "ADMIN".equals(r.getCode()) || "CUSTOMER".equals(r.getCode())) {
                throw new BusinessException(CommonErrorCode.FORBIDDEN, "当前账号无权授予该角色：" + r.getCode());
            }
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "该角色不可经本接口授予：" + r.getCode());
        }
        return found;
    }

    /** 全量替换本店角色：先逻辑删干净再插（同一事务内，失败整体回滚）。 */
    private void applyRoles(Long employeeId, Long storeId, List<EmployeeManageMapper.CodeRow> roles) {
        mapper.softDeleteRoles(employeeId, storeId);
        roles.forEach(r -> mapper.insertRole(IdWorker.getId(), employeeId, r.getId(), storeId));
    }

    /** 并发/唯一键兜底：uk_user、uk_emp_store、uk_emp_role_store、uk_emp_skill 都是最后防线。 */
    private void guard(Runnable body) {
        try {
            body.run();
        } catch (DuplicateKeyException e) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "该员工资料刚被他人变更，请刷新后重试");
        }
    }

    private EmployeeManageView view(EmployeeManageMapper.EmployeeRow emp, Long storeId,
                                    boolean employeeCreated, boolean affiliationCreated) {
        Long employeeId = emp.getId();
        return EmployeeManageView.builder()
                .employeeId(employeeId)
                .userId(emp.getUserId())
                .name(emp.getName())
                .employeeNo(emp.getEmployeeNo())
                // storeId 可空（ADR-050 全局编辑没有门店语境）：角色列按给定语境查，无语境自然为空集
                .storeId(storeId)
                .employeeCreated(employeeCreated)
                .affiliationCreated(affiliationCreated)
                .primaryStore(storeId != null && storeId.equals(mapper.selectPrimaryStore(employeeId)))
                .roleCodes(mapper.selectRoleCodes(employeeId, storeId))
                .skillCodes(mapper.selectSkillCodes(employeeId))
                .build();
    }

    private static List<String> distinctNonBlank(Collection<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().filter(s -> s != null && !s.isBlank()).map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new)).stream().sorted().toList();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
