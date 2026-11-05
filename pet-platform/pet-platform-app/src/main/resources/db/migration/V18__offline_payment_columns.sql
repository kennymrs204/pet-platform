-- ============================================================================
-- V18：线下收款登记/复核（订单管理链路打通 P2，ADR-006 §6）
--
-- pay_payment 补三列，支撑"前台登记 → 他人复核 → 推进 PAID"的双人原则：
--   registered_by  登记人 userId（复核人≠登记人的硬闸判据）
--   registered_note 登记备注（线下收款说明，≤200，NULL 允许）
--   confirmed_by   复核人 userId（复核成功时与 channel_txn_no 同事务写入）
--
-- OFFLINE 行只经 PaymentService.registerOffline 创建，登记即置三列之一；
-- 复核复用 CAS PROCESSING→SUCCESS（updateOfflineSuccess），成功后发布
-- PaymentSucceededEvent(channel=OFFLINE)，既有桥接链（订单 PAID → 预约 CONFIRMED
-- → 入住单 EXPECTED）原样生效，零新增消费者。
-- 存量行三列均为 NULL，读侧按 NULL 渲染。
-- ============================================================================

ALTER TABLE pay_payment
    ADD COLUMN registered_by BIGINT NULL AFTER callback_raw,
    ADD COLUMN registered_note VARCHAR(200) NULL AFTER registered_by,
    ADD COLUMN confirmed_by BIGINT NULL AFTER registered_note;
