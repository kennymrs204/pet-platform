-- ============================================================================
-- V5：把「平台管理员」放大到可代入任意门店（ADR-043 / docs/B端ADMIN平台权限放大-阶段0可行性盘点与方案.md）
--
-- 补的是什么：ADMIN 现有 45/74 码，缺的 29 个里只有这 17 个属于「门店执行档」——
--   它们决定了 ADMIN 进入某家店后能不能真的干活（签到/开始完成服务/办入住/写履约与诊疗记录/看本店客户）。
--   注意：光有码还不够，真正的门是 PermissionAssembler 的「ctx ∈ 该店 ACTIVE 归属」四表 JOIN，
--   那一条在同版本里以「平台管理员分支」放开；本迁移只负责码集本身。
--
-- 刻意不补的 12 个：appointment:view:self / appointment:cancel:self / appointment:create /
--   order:view:self / order:refund:apply / pet:create / pet:update / pet:delete / pet:relation:manage /
--   rec:browse / rec:apply / rec:application:view:self
--   —— 这些的主语是「顾客本人」，服务层按 userId 过滤；给 ADMIN 不会让他多看见任何东西，
--      只会让「本人」语义失真、并凭空多出一类「有码但恒空」的解释成本。
--   其中 appointment:create（代客下单）是业务待拍板项，不因「放大权限」顺带开出。
--
-- 幂等：NOT EXISTS 逐码判重，可反复执行；只 INSERT，不 UPDATE/DELETE 任何已应用迁移的行。
-- 主键续号：V2 占 200001-200232，V3/V4 各追加过权限行 → 必须 MAX(id)+1 起，不能猜。
-- ============================================================================

SET @i := (SELECT COALESCE(MAX(id), 0) FROM emp_role_permission);

INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT @i := @i + 1, r.id, x.code
FROM emp_role r
JOIN (
  SELECT 'appointment:checkin'          AS code UNION ALL
  SELECT 'appointment:view:task'                 UNION ALL
  SELECT 'task:start'                            UNION ALL
  SELECT 'task:finish'                           UNION ALL
  SELECT 'record:bath:create'                    UNION ALL
  SELECT 'record:walk:create'                    UNION ALL
  SELECT 'boarding:checkin'                      UNION ALL
  SELECT 'boarding:checkout'                     UNION ALL
  SELECT 'boarding:care:create'                  UNION ALL
  SELECT 'customer:view:store'                   UNION ALL
  SELECT 'medical:record:create'                 UNION ALL
  SELECT 'medical:record:update'                 UNION ALL
  SELECT 'medical:prescription:create'           UNION ALL
  SELECT 'medical:vaccine:manage'                UNION ALL
  SELECT 'medical:report:upload'                 UNION ALL
  SELECT 'order:pay'                             UNION ALL
  SELECT 'pet:health:manage'
) x
WHERE r.code = 'ADMIN' AND r.deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM emp_role_permission p
    WHERE p.role_id = r.id AND p.permission_code = x.code
  );

-- 自检：应为 17（放大后 ADMIN 共 62 码），且这 12 个"本人档"仍不在 ADMIN 手里
SELECT 'ADMIN 码数（期望 62）' AS k, COUNT(*) AS n
  FROM emp_role_permission rp JOIN emp_role r ON r.id = rp.role_id WHERE r.code = 'ADMIN'
UNION ALL
SELECT '仍无 appointment:create', COUNT(*) = 0
  FROM emp_role_permission rp JOIN emp_role r ON r.id = rp.role_id
 WHERE r.code = 'ADMIN' AND rp.permission_code = 'appointment:create'
UNION ALL
SELECT '仍无 order:view:self', COUNT(*) = 0
  FROM emp_role_permission rp JOIN emp_role r ON r.id = rp.role_id
 WHERE r.code = 'ADMIN' AND rp.permission_code = 'order:view:self';

-- ============================== 回滚 =========================================
-- DELETE rp FROM emp_role_permission rp JOIN emp_role r ON r.id = rp.role_id
--  WHERE r.code = 'ADMIN' AND rp.permission_code IN (
--    'appointment:checkin','appointment:view:task','task:start','task:finish',
--    'record:bath:create','record:walk:create','boarding:checkin','boarding:checkout',
--    'boarding:care:create','customer:view:store','medical:record:create','medical:record:update',
--    'medical:prescription:create','medical:vaccine:manage','medical:report:upload',
--    'order:pay','pet:health:manage');
-- 回滚后必须同时清 Redis：DEL perm:{adminUserId}:{anyCtx}（见下条注意），否则已登录 ADMIN 仍持旧码集到 TTL。

-- ⚠ 部署注意（本仓踩过两次的同类坑）：权限码集缓存在 perm:{userId}:{storeCtx}，
--   而 PermissionAssembler 把「缓存非空」当命中（UNIVERSAL_CODES 含 account:profile:manage，恒非空）→
--   **迁移生效后，已登录 ADMIN 的旧缓存里那枚"只有一条通用码"的集合会被继续命中，症状是仍然 10003，
--   最长持续到 access TTL（默认 7200s）**。上线后对 ADMIN 账号执行：
--     docker exec pet-platform-redis redis-cli -a <pwd> --scan --pattern 'perm:*' | xargs -r redis-cli DEL
--   或让其重新登录（新 jti 不共享旧缓存键时仍需清，最稳妥是清 perm:*）。
