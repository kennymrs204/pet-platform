-- ============================================================================
-- V7：门店代客建档（ADR-057）
-- ① usr_customer_profile 加 origin_store_id：本店客户的第二来源（预约之外的本店建档）
-- ② 新增权限码 customer:create，授 STORE_MANAGER + RECEPTIONIST；ADMIN 经平台放大补齐
-- 不建表、不动 V1~V6。重复执行由 NOT EXISTS 兜底；加列只跑一次（Flyway 记录在案）。
-- ============================================================================

-- ① 加列：可空，跨模块 ID 不建 FK（与 preferred_store_id 同约定）；存量行一律 NULL=非建档来源
ALTER TABLE usr_customer_profile
  ADD COLUMN origin_store_id BIGINT NULL
  COMMENT '建档门店（跨模块ID，无FK）；ADR-057：本店客户 = 本店预约 ∪ 本店建档'
  AFTER preferred_store_id;

-- ② 新码 customer:create：店长 + 前台（照 V3 写法；任务角色刻意不授——他们不该建客户档）
SET @i := (SELECT COALESCE(MAX(id), 0) FROM emp_role_permission);
INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT (@i := @i + 1), r.id, 'customer:create'
FROM emp_role r
WHERE r.code IN ('STORE_MANAGER', 'RECEPTIONIST')
  AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM emp_role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_code = 'customer:create');

-- ③ ADMIN 平台放大（照 V5 写法；平台代管门店时要能代客建档）
SET @i := (SELECT COALESCE(MAX(id), 0) FROM emp_role_permission);
INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT (@i := @i + 1), r.id, 'customer:create'
FROM emp_role r
WHERE r.code = 'ADMIN'
  AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM emp_role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_code = 'customer:create');

-- 自检：customer:create 持有者应为 ADMIN,RECEPTIONIST,STORE_MANAGER（断集合，别断条数——ADR-044 教训）
-- SELECT GROUP_CONCAT(r.code ORDER BY r.code) FROM emp_role_permission rp JOIN emp_role r ON r.id=rp.role_id
--  WHERE rp.permission_code='customer:create';
