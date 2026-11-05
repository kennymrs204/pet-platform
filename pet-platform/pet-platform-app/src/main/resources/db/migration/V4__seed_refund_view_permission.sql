-- ============================================================================
-- V4：退款进度可见性权限种子（ADR-034 §3）
-- 新增能力码 order:refund:view，授予**全部 7 个员工角色**（ADMIN/STORE_MANAGER/DOCTOR/
-- GROOMER/DOG_WALKER/CARETAKER/RECEPTIONIST），不建表、不加列，V1/V2/V3 不动。
--
-- 为什么要单独一条码：审批权 order:refund:approve 只给店长与平台管理员（V2 既有，正好符合
-- 业务要求），但"看得到退款进度"是全店员工的协作需要——而任务角色连 order:view:store 都没有
-- （V2 只给 ADMIN/STORE_MANAGER/RECEPTIONIST），复用它会顺带放开整张订单列表与实付金额。
-- 所以这里单开一条**只够读退款**的码，避免"想看退款进度结果拿到了订单读权限"。
--
-- 客户侧不授此码：C 端查本人退款进度走既有的 order:view:self + 归属校验（user_id=本人）。
-- id 取现值 MAX+1 递增，重复执行由 NOT EXISTS + 唯一键双兜底（V3 同法）。
-- ============================================================================

SET @i := (SELECT COALESCE(MAX(id), 0) FROM emp_role_permission);

INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT (@i := @i + 1), r.id, 'order:refund:view'
FROM emp_role r
WHERE r.code IN ('ADMIN', 'STORE_MANAGER', 'DOCTOR', 'GROOMER', 'DOG_WALKER', 'CARETAKER', 'RECEPTIONIST')
  AND NOT EXISTS (SELECT 1 FROM emp_role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_code = 'order:refund:view');
