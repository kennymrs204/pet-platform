-- ============================================================================
-- V10：代客下单拍板 —— appointment:create 补授 STORE_MANAGER + ADMIN（B 端「新增预约」）
--
-- 背景：V2 种子给 RECEPTIONIST（前台）发过 appointment:create（当时的"代客下单"设计）；
--   V5 把 ADMIN 的补授写成"业务待拍板项，不因放大权限顺带开出"——2026-10-15 B 端
--   「新增预约」（客户 360 → 代客建单）落地即拍板时刻。店长是客户管理页的主用户
--   （customer:view:store 的持有者），无此码则建单口 10003；ADMIN 依 ADR-044 可代入门店，
--   同口径补齐（代入门店后能干活）。不新造码：B 端与 C 端建单走同一条创建链（同表同校验）。
--
-- 幂等：NOT EXISTS 逐码判重，可反复执行；只 INSERT，不 UPDATE/DELETE 任何已应用迁移的行。
-- 主键续号：V2 占 200001-200232，V3/V4/V5/V7/V8 各追加过权限行 → 必须 MAX(id)+1 起，不能猜。
-- ============================================================================

SET @i := (SELECT COALESCE(MAX(id), 0) FROM emp_role_permission);

INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT @i := @i + 1, r.id, 'appointment:create'
FROM emp_role r
WHERE r.code IN ('STORE_MANAGER', 'ADMIN') AND r.deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM emp_role_permission p
    WHERE p.role_id = r.id AND p.permission_code = 'appointment:create'
  );

-- 自检：两个角色都应持有（各期望 1；前台 V2 起已有，不在本迁移射程内）
SELECT 'STORE_MANAGER 持有 appointment:create' AS k, COUNT(*) AS n
  FROM emp_role_permission rp JOIN emp_role r ON r.id = rp.role_id
 WHERE r.code = 'STORE_MANAGER' AND rp.permission_code = 'appointment:create'
UNION ALL
SELECT 'ADMIN 持有 appointment:create', COUNT(*)
  FROM emp_role_permission rp JOIN emp_role r ON r.id = rp.role_id
 WHERE r.code = 'ADMIN' AND rp.permission_code = 'appointment:create';

-- ============================== 回滚 =========================================
-- DELETE rp FROM emp_role_permission rp JOIN emp_role r ON r.id = rp.role_id
--  WHERE r.code IN ('STORE_MANAGER','ADMIN') AND rp.permission_code = 'appointment:create';
-- 回滚后必须同时清 Redis（见下），否则已登录账号仍持旧码集到 TTL。

-- ⚠ 部署注意（V8/ADR-058 踩过的同类坑）：权限码集缓存在 perm:{userId}:{storeCtx}，
--   加码不失效旧缓存 → 已登录店长会继续 10003。上线后执行：
--     docker exec pet-platform-redis redis-cli -a <pwd> --scan --pattern 'perm:*' | xargs -r redis-cli DEL
--   或让相关账号重新登录。
