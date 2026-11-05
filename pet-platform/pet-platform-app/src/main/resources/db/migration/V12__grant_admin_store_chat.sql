-- ============================================================================
-- V12：ADMIN 代店沟通补授（P2.2）—— store:chat:view / store:chat:send → ADMIN
--
-- 背景：V11 把沟通码授给 STORE_MANAGER + RECEPTIONIST；ADMIN 依 ADR-044 可代入任意
--   OPEN 门店，但 PermissionAssembler 的放大只并入 **ADMIN 角色自身码集**——V11 未扩充
--   该码集，致 ADMIN 代店后沟通族 10003（与 V5 补 17 个门店执行码、V10 补
--   appointment:create 完全同先例；本迁移即为该缺口的纯授权修正）。
--
-- 授权边界（已确认，仅加码、不改任何放行逻辑）：
--   ① 门店选择：PermissionAssembler.canImpersonateStore 的既有 OPEN 硬闸——ADMIN 只在
--      "合法 X-Store-Id 且门店存在且 OPEN"的上下文内并入 ADMIN 码集（停业店不放大）；
--   ② 数据范围：ChatGuard 以 store_id == 当前 ctx 收口——ADMIN 只能看/发该门店的会话，
--      跨店隔离与其余角色一致，不放宽。
--
-- 幂等：NOT EXISTS 逐码判重，可反复执行；只 INSERT，不 UPDATE/DELETE 任何已应用迁移的行。
-- 主键续号：必须 MAX(id)+1 起（V2~V11 已占若干权限行，不能猜）。
-- 不触碰其它角色：STORE_MANAGER / RECEPTIONIST / CUSTOMER 的既有授权原样；不新增权限码。
--
-- ⚠ 部署注意（V8/V10/V11 同类坑）：权限码集缓存在 perm:{userId}:{storeCtx}（另有
--   perm:eid:{userId}:{storeCtx}），加码不失效旧缓存。上线后按安全方式清理（⚠ DEL 不展开
--   通配符，必须先 SCAN 再删）：
--     docker exec pet-platform-redis redis-cli -a <pwd> --scan --pattern 'perm:*' \
--       | xargs -r docker exec -i pet-platform-redis redis-cli -a <pwd> DEL
--   或让相关账号重新登录。
-- ============================================================================

SET @i := (SELECT COALESCE(MAX(id), 0) FROM emp_role_permission);

INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT @i := @i + 1, r.id, x.code
FROM emp_role r
JOIN (SELECT 'store:chat:view' AS code UNION ALL SELECT 'store:chat:send') x
WHERE r.code = 'ADMIN' AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM emp_role_permission p
                  WHERE p.role_id = r.id AND p.permission_code = x.code);

-- 自检：ADMIN 期望 2；STORE_MANAGER / RECEPTIONIST 应保持各 2（不受本迁移影响）
SELECT 'ADMIN 持 store:chat:view+send' AS k, COUNT(*) AS n
  FROM emp_role_permission rp JOIN emp_role r ON r.id = rp.role_id
 WHERE r.code = 'ADMIN' AND rp.permission_code IN ('store:chat:view', 'store:chat:send')
UNION ALL
SELECT 'STORE_MANAGER 保持 2', COUNT(*)
  FROM emp_role_permission rp JOIN emp_role r ON r.id = rp.role_id
 WHERE r.code = 'STORE_MANAGER' AND rp.permission_code IN ('store:chat:view', 'store:chat:send')
UNION ALL
SELECT 'RECEPTIONIST 保持 2', COUNT(*)
  FROM emp_role_permission rp JOIN emp_role r ON r.id = rp.role_id
 WHERE r.code = 'RECEPTIONIST' AND rp.permission_code IN ('store:chat:view', 'store:chat:send');

-- ============================== 回滚 =========================================
-- DELETE rp FROM emp_role_permission rp JOIN emp_role r ON r.id = rp.role_id
--  WHERE r.code = 'ADMIN' AND rp.permission_code IN ('store:chat:view', 'store:chat:send');
-- 回滚后必须清 Redis（见上方部署注意），否则已登录 ADMIN 持旧码集到 TTL。
