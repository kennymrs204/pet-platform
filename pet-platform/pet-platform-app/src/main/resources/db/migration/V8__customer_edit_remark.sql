-- ============================================================================
-- V8：门店侧编辑客户资料（ADR-058）
-- ① usr_customer_profile 加 remark：门店侧客户备注（360 里"客户备注"占位转真值）
-- ② 新增权限码 customer:update（改资料 + 代建宠物共用一个码），授 STORE_MANAGER + RECEPTIONIST；
--    ADMIN 经平台放大补齐（代管门店时要能改档）
-- 不建表、不动 V1~V7。重复执行由 NOT EXISTS 兜底；加列只跑一次（Flyway 记录在案）。
-- ============================================================================

-- ① 加列：可空（空=未填写）；≤500 与 C 端/宠物备注同量级
ALTER TABLE usr_customer_profile
  ADD COLUMN remark VARCHAR(500) NULL
  COMMENT '门店侧客户备注（ADR-058）；C 端不展示'
  AFTER origin_store_id;

-- ② customer:update：店长 + 前台（照 V3/V7 写法；任务角色刻意不授）
SET @i := (SELECT COALESCE(MAX(id), 0) FROM emp_role_permission);
INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT (@i := @i + 1), r.id, 'customer:update'
FROM emp_role r
WHERE r.code IN ('STORE_MANAGER', 'RECEPTIONIST')
  AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM emp_role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_code = 'customer:update');

-- ③ ADMIN 平台放大
SET @i := (SELECT COALESCE(MAX(id), 0) FROM emp_role_permission);
INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT (@i := @i + 1), r.id, 'customer:update'
FROM emp_role r
WHERE r.code = 'ADMIN'
  AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM emp_role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_code = 'customer:update');

-- 自检：customer:update 持有者应为 ADMIN,RECEPTIONIST,STORE_MANAGER（断集合，别断条数——ADR-044 教训）
-- SELECT GROUP_CONCAT(r.code ORDER BY r.code) FROM emp_role_permission rp JOIN emp_role r ON r.id=rp.role_id
--  WHERE rp.permission_code='customer:update';
