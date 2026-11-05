package com.petplatform.app.security;

import com.petplatform.employee.api.EmployeePermissionApi;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.store.api.StoreQueryApi;
import com.petplatform.user.api.UserAccountApi;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * 权限装配器（第八阶段 §1.2 装配链，app 是唯一可同时依赖 user-api 与
 * employee-api 的装配点——user 模块按 §3.2 矩阵不依赖 employee）。
 *
 * storeCtx=0（C 端）：账号 NORMAL + 有 CustomerProfile 才并入 CUSTOMER 权限码（ADR-001-J）；employeeId=0。
 * storeCtx>0（B 端）：emp_employee_role(当前 storeCtx) → emp_role_permission；
 *   X-Store-Id 归属校验内含于 storePermissions 的 SQL（非 ACTIVE 归属 = 空集）；
 *   employeeId 经 EmployeeQueryApi.employeeIdInStore 解析（token 的 eid 仅签发期占位，user 模块不查 emp_*）。
 * 例外（ADR-044，落地矩阵:54「ADMIN 看所有门店」）：持有平台 ADMIN 角色者，对**任意 OPEN 门店**
 *   并入 ADMIN 角色的码集——他不需要在该店有归属。三条边界：① 只加码不改身份，employeeId 仍按该店归属解析
 *   （在别店自然是 0，于是 TASK/SELF 档视图自动为空，不会伪装成员工）；② 门店不存在或非 OPEN 不放大，
 *   结果与任何"无归属 ctx"一样是 10003，不给"全码 + 空数据"这种最难误诊的形态；
 *   ③ 门店侧每一次授权判定仍走 storePermissions——"权限来自该店归属"这条地基不从这边绕过去。
 * 全角色通用码 account:profile:manage：第一阶段 §4.1 该能力 8 角色全 ◎，
 * 保证新注册用户（尚无 CustomerProfile）可管理本人账号资料，非矩阵外发明。
 *
 * 结果写 Redis perm:{userId}:{storeCtx}（码集）+ perm:eid:{userId}:{storeCtx}（员工 id），
 * 二者同 TTL = access 剩余有效期（§1.2），evict 时一并删除。
 *
 * <p>⚠ 权限种子变更（如 V5 给 ADMIN 补码）后，本类**不会**自动失效旧缓存：UNIVERSAL_CODES 非空，
 * 所以"只含通用码"的旧集合会被当成命中继续返回。部署时必须清 {@code perm:*}，否则最长到 access TTL
 * 才生效——症状与"改了没生效"一模一样（V5 文件末尾有同一条注意）。
 */
@Component
public class PermissionAssembler {

    /** 第一阶段 §4.1：account:register/login/profile 全角色 ◎ S */
    private static final Set<String> UNIVERSAL_CODES = Set.of("account:profile:manage");
    private static final String CUSTOMER_ROLE = "CUSTOMER";
    /** 平台管理员角色码（ADR-044）：唯一可跨门店代入的角色。 */
    private static final String PLATFORM_ADMIN_ROLE = "ADMIN";
    private static final String KEY_PERM = "perm:%d:%d";
    private static final String KEY_PERM_EID = "perm:eid:%d:%d";

    private final UserAccountApi userAccountApi;
    private final EmployeePermissionApi employeePermissionApi;
    private final EmployeeQueryApi employeeQueryApi;
    private final StoreQueryApi storeQueryApi;
    private final StringRedisTemplate redis;

    public PermissionAssembler(UserAccountApi userAccountApi, EmployeePermissionApi employeePermissionApi,
                               EmployeeQueryApi employeeQueryApi, StoreQueryApi storeQueryApi,
                               StringRedisTemplate redis) {
        this.userAccountApi = userAccountApi;
        this.employeePermissionApi = employeePermissionApi;
        this.employeeQueryApi = employeeQueryApi;
        this.storeQueryApi = storeQueryApi;
        this.redis = redis;
    }

    /** 装配结果：员工身份 id（C 端或非员工为 0）+ 权限码集合。 */
    public record Assembled(Long employeeId, Set<String> codes) {}

    /**
     * 读缓存 → miss 现场装配并回写缓存（§1.2 装配链唯一入口）。
     * @return 装配结果；账号不存在或已封禁返回 null（调用方按未认证处理）。
     */
    public Assembled resolve(Long userId, Long storeCtx, long ttlSeconds) {
        Set<String> cached = readCache(userId, storeCtx);
        // SMEMBERS 对不存在的 key 返回空集而非 null；装配结果必含通用码，空集一律视为 miss
        if (cached != null && !cached.isEmpty()) {
            return new Assembled(readCachedEmployeeId(userId, storeCtx), cached);
        }
        return assemble(userId, storeCtx, ttlSeconds);
    }

    private Assembled assemble(Long userId, Long storeCtx, long ttlSeconds) {
        String status = userAccountApi.statusOf(userId);
        if (status == null || "DISABLED".equals(status)) {
            return null;
        }
        Set<String> codes = new HashSet<>(UNIVERSAL_CODES);
        Long employeeId = 0L;
        if (storeCtx == 0L) {
            if (userAccountApi.existsCustomerProfile(userId)) {
                codes.addAll(employeePermissionApi.roleCodes(CUSTOMER_ROLE));
            }
        } else {
            codes.addAll(employeePermissionApi.storePermissions(userId, storeCtx));
            if (canImpersonateStore(userId, storeCtx)) {
                // 只加码、不改身份：employeeId 仍由下面的"该店归属"解析（在别店自然为 0）
                codes.addAll(employeePermissionApi.roleCodes(PLATFORM_ADMIN_ROLE));
            }
            Long eid = employeeQueryApi.employeeIdInStore(userId, storeCtx);
            employeeId = eid == null ? 0L : eid;
        }
        cache(userId, storeCtx, employeeId, codes, ttlSeconds);
        return new Assembled(employeeId, codes);
    }

    /**
     * 平台管理员能否代入该店（ADR-044）。两个条件缺一不可：
     * 持有全局 ADMIN 角色；且该店存在并 OPEN——对不存在/已停业/已关闭的门店不放大，
     * 否则 ADMIN 带一个错店 id 会拿到"全码 + 零数据"，症状与权限故障一模一样，最难误诊。
     */
    private boolean canImpersonateStore(Long userId, Long storeCtx) {
        if (storeCtx == null || storeCtx == 0L
                || !employeePermissionApi.holdsGlobalRole(userId, PLATFORM_ADMIN_ROLE)) {
            return false;
        }
        var store = storeQueryApi.store(storeCtx);
        return store != null && "OPEN".equals(store.getStatus());
    }

    public Set<String> readCache(Long userId, Long storeCtx) {
        return redis.opsForSet().members(String.format(KEY_PERM, userId, storeCtx));
    }

    private Long readCachedEmployeeId(Long userId, Long storeCtx) {
        String v = redis.opsForValue().get(String.format(KEY_PERM_EID, userId, storeCtx));
        if (v == null || v.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 删掉某人在某上下文下的权限缓存，下次请求现场重建（§1.2）。
     *
     * <p>员工角色 / 门店归属 / 状态变更的写侧端点会在操作完成后调用本方法
     * （见 {@code AdminEmployeeRoleController}、{@code StoreEmployeeManageController}、
     * {@code AdminEmployeeStatusController}）；未覆盖的变更路径仍以权限缓存 TTL
     * （=access 剩余有效期）兜底，必要时可手工 {@code DEL perm:{uid}:{ctx}}。
     */
    public void evict(Long userId, Long storeCtx) {
        redis.delete(String.format(KEY_PERM, userId, storeCtx));
        redis.delete(String.format(KEY_PERM_EID, userId, storeCtx));
    }

    /**
     * 用户级全上下文失效（ADR-055 全局停用/离职）：SCAN 删 {@code perm:{userId}:*} 与 {@code perm:eid:{userId}:*}。
     *
     * <p>与 {@link #evict} 的分工：单店写侧只动被改的那家店；全局状态变更涉及该用户在**所有** storeCtx
     * （含 ctx=0）下的缓存，漏一个就等于"离职了还能干活"。pattern 前缀带冒号，`perm:123:*` 不会
     * 误吃 `perm:1234:*`。
     */
    public void evictAll(Long userId) {
        deleteByPattern(String.format("perm:%d:*", userId));
        deleteByPattern(String.format("perm:eid:%d:*", userId));
    }

    private void deleteByPattern(String pattern) {
        Set<String> keys = new HashSet<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(100).build())) {
            cursor.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private void cache(Long userId, Long storeCtx, Long employeeId, Set<String> codes, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            return;
        }
        Duration ttl = Duration.ofSeconds(ttlSeconds);
        String key = String.format(KEY_PERM, userId, storeCtx);
        redis.delete(key);
        redis.opsForSet().add(key, codes.toArray(String[]::new));
        redis.expire(key, ttl);
        redis.opsForValue().set(String.format(KEY_PERM_EID, userId, storeCtx),
                String.valueOf(employeeId == null ? 0L : employeeId), ttl);
    }
}
