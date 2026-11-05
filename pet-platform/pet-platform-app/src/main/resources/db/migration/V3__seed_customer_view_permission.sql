-- ============================================================================
-- V3：B 端客户视图权限种子（ADR-017 §2）
-- 新增第 13 个业务域能力 customer:view:store，仅授 STORE_MANAGER 与 RECEPTIONIST。
-- 不建表、不加列、不改权限码字典表；V1/V2 不动。
-- 任务角色（美容师/遛狗师/护理托管员/医生）刻意不授：他们不应看到客户手机号与消费额，
-- 与 ADR-014「任务角色数据范围 = assigned ∪ caretaker」的收敛方向一致。
-- id 取现值 MAX+1 递增，避免与 V2 的 @i := 200000 段撞号；重复执行由唯一键兜底。
-- ============================================================================

SET @i := (SELECT COALESCE(MAX(id), 0) FROM emp_role_permission);

INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT (@i := @i + 1), r.id, 'customer:view:store'
FROM emp_role r
WHERE r.code IN ('STORE_MANAGER', 'RECEPTIONIST')
  AND NOT EXISTS (SELECT 1 FROM emp_role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_code = 'customer:view:store');
