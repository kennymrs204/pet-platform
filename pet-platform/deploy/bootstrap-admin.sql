-- ============================================================================
-- 平台管理员初始化脚本（手工执行，不参与 Flyway）
--
-- 背景：Flyway 只种角色/权限/服务目录与「平台总部」门店，不含任何账号或员工。
--       全新库必须先执行本脚本，B 端才有一个可登录的管理员；否则员工创建、
--       门店配置等入口都没有权限入口（鸡生蛋）。
--
-- 前置：先注册一个手机号（C 端注册，验证码见后端日志 [DEV-SMS]），
--       或使用开发默认账号 13800000000。
--
-- 执行（Git Bash / Linux，中文内容必须走 stdin，避免 Windows 代码页转码）：
--   docker exec -i pet-platform-mysql \
--     mysql -uroot -p"$PET_DB_PASSWORD" pet_platform < deploy/bootstrap-admin.sql
--
-- 幂等：可重复执行；已存在的员工/归属/角色不会重复插入。
-- ============================================================================

SET NAMES utf8mb4;

-- ---- 参数：按需修改 --------------------------------------------------------
SET @admin_phone  = '13800000000';          -- 要提升为管理员的账号手机号
SET @admin_name   = '平台管理员';            -- 员工姓名
SET @admin_role   = 'ADMIN';                -- 角色码（V2 种子：ADMIN=102）
-- 管理员挂靠的门店：优先复用库中第一家 NORMAL 门店；没有则创建 9111
SET @store_id     = (SELECT id FROM sto_store WHERE store_type = 'NORMAL' AND deleted = 0 ORDER BY id LIMIT 1);

INSERT INTO sto_store (id, name, phone, address, status, store_type)
SELECT 9111, '默认门店', '0000000000', '请按实际地址修改', 'OPEN', 'NORMAL'
FROM DUAL
WHERE @store_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM sto_store WHERE id = 9111);

-- 重新解析：若刚创建则取 9111；没有可用 NORMAL 门店则为 NULL（脚本会在自检里报错）
SET @store_id = (SELECT id FROM sto_store WHERE store_type = 'NORMAL' AND deleted = 0 ORDER BY id LIMIT 1);
SET @admin_role_id = (SELECT id FROM emp_role WHERE code = @admin_role AND deleted = 0 LIMIT 1);

-- ---- 1. 账号 → 员工身份 ----------------------------------------------------
SET @uid = (SELECT id FROM usr_user WHERE phone = @admin_phone AND deleted = 0 LIMIT 1);

INSERT INTO emp_employee (id, user_id, employee_no, name, status, hired_at)
SELECT UUID_SHORT(), @uid, 'ADMIN-001', @admin_name, 'ACTIVE', CURDATE()
FROM DUAL
WHERE @uid IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emp_employee WHERE user_id = @uid);

SET @eid = (SELECT id FROM emp_employee WHERE user_id = @uid AND deleted = 0 LIMIT 1);

-- ---- 2. 员工 → 门店归属 ----------------------------------------------------
INSERT INTO emp_employee_store (id, employee_id, store_id, is_primary, status, started_at)
SELECT UUID_SHORT(), @eid, @store_id, 1, 'ACTIVE', CURDATE()
FROM DUAL
WHERE @eid IS NOT NULL AND @store_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emp_employee_store
                  WHERE employee_id = @eid AND store_id = @store_id AND deleted = 0);

-- ---- 3. 员工 → ADMIN 角色（挂在该门店） ------------------------------------
INSERT INTO emp_employee_role (id, employee_id, role_id, store_id)
SELECT UUID_SHORT(), @eid, @admin_role_id, @store_id
FROM DUAL
WHERE @eid IS NOT NULL AND @admin_role_id IS NOT NULL AND @store_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM emp_employee_role
                  WHERE employee_id = @eid AND role_id = @admin_role_id
                    AND store_id = @store_id AND deleted = 0);

-- ---- 自检 ------------------------------------------------------------------
SELECT
  @admin_phone AS admin_phone,
  @uid         AS user_id,
  @eid         AS employee_id,
  @store_id    AS store_id,
  (SELECT COUNT(*) FROM emp_employee_store
    WHERE employee_id = @eid AND store_id = @store_id AND status = 'ACTIVE' AND deleted = 0) AS affiliation_rows,
  (SELECT COUNT(*) FROM emp_employee_role
    WHERE employee_id = @eid AND role_id = @admin_role_id AND store_id = @store_id AND deleted = 0) AS admin_role_rows,
  IF(@uid IS NULL, '账号不存在：请先用该手机号注册',
     IF(@store_id IS NULL, '未找到普通门店：请先创建 sto_store 后重跑',
        IF(@admin_role_id IS NULL, 'ADMIN 角色缺失：请确认 Flyway V2 已执行',
           IF(@eid IS NULL, '员工创建失败：请检查 emp_employee 唯一键', 'OK')))) AS result;

-- ---- 注意 ------------------------------------------------------------------
-- 1) 若该账号此前已登录并产生权限缓存，改完需清理后重新登录（<uid> 用上面自检输出）：
--      docker exec pet-platform-redis redis-cli -a "$PET_REDIS_PASSWORD" --no-auth-warning DEL "perm:<uid>:0"
--      docker exec pet-platform-redis redis-cli -a "$PET_REDIS_PASSWORD" --no-auth-warning DEL "perm:eid:<uid>:0"
--      以及该员工门店上下文 "perm:<uid>:<store_id>" / "perm:eid:<uid>:<store_id>"
-- 2) B 端登录后门店上下文来自 GET /auth/employee-identities，可看到上面挂靠的门店。
-- 3) 管理员可代入任意 OPEN 门店；日常门店数据仍需在 B 端配置服务价目、资源与员工。
