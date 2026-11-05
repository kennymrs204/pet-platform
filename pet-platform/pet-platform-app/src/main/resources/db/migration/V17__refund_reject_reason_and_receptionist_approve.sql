-- ============================================================================
-- V17：退款拒绝理由 + 前台退款审批同权（订单管理链路打通 P1）
-- 两件事，均无破坏性：
--
-- 1) ord_refund 补 reject_reason（C4 欠账）：此前被拒时顾客看不到原因（ADR-037 §5
--    "本期不记录"是当时的挂账口径）。列宽与申请理由 reason 一致（VARCHAR(200) NULL），
--    存量 REJECTED 行保持 NULL，读侧回退"门店未登记拒绝理由"文案。
--
-- 2) RECEPTIONIST += order:refund:approve：订单管理三角色（店长/前台/平台管理员）同权口径
--    （2026-10-25 项目所有者拍板）。此前 V2 只授 ADMIN/STORE_MANAGER，实测前台持
--    order:pay/order:view:store 却不能审退款。资金安全闸不放宽：审批额度（sys_config 507）
--    与限本店照旧按 RefundApprovalService 既有逻辑生效，ADMIN 仍跳过两道闸。
--    NOT EXISTS + 幂等：与 V3/V4 同法。
-- ============================================================================

ALTER TABLE ord_refund
    ADD COLUMN reject_reason VARCHAR(200) NULL AFTER reason;

SET @i := (SELECT COALESCE(MAX(id), 0) FROM emp_role_permission);

INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT (@i := @i + 1), r.id, 'order:refund:approve'
FROM emp_role r
WHERE r.code = 'RECEPTIONIST'
  AND NOT EXISTS (SELECT 1 FROM emp_role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_code = 'order:refund:approve');
