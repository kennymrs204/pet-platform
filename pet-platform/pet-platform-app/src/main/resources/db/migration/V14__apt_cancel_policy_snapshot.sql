-- ============================================================================
-- V14：取消政策快照（S7，ADR-060）——apt_appointment 扩展两个快照列
--
-- 范围：仅 ALTER apt_appointment 加 2 列 + 自检。**不改任何既有列/约束/索引**，
--   不新增权限码（读写两侧均复用既有 appointment 链）——无 Redis 缓存需清理
--   （与 V8/V10/V12 的授权迁移不同，本迁移无需 DEL perm:*）。
-- 语义（补 ADR-023 §5 / 第五阶段 :138 C4 记录的缺口："快照含取消政策"当时未落地）：
--   cancel_free_hours_snapshot  INT  仅新单：创建时"当时有效值"（缺省 24 + 原值）；
--   cancel_penalty_rate_snapshot INT 仅新单：创建时"当时有效值"（钳 0..100 + 缺省 0）。
--   取消退款按快照计算——**不再随 cat_store_service 后续原地覆盖而漂移**；
--   历史行两列保持 NULL（数据库无历史政策真值，不伪造、不回填）→ 取消时整体
--   fallback 当前政策。快照列创建后不可变（无任何 UPDATE 路径），
--   与 price/duration/service_name_snapshot 同族（ADR-002-8"详情自足"）。
--
-- ⚠ 部署注意：V14 应用后，**只含 V1~V13 的旧镜像不可再启动**（Flyway
--   validate-on-migrate 会因库版本高于本地最新迁移而失败）——须用含 V14 的新镜像重建。
-- ============================================================================

ALTER TABLE apt_appointment
  ADD COLUMN cancel_free_hours_snapshot INT NULL
      COMMENT '取消政策快照:免取消窗口小时(创建时有效值,S7;历史行 NULL=fallback 现政策)' AFTER duration_snapshot,
  ADD COLUMN cancel_penalty_rate_snapshot INT NULL
      COMMENT '取消政策快照:扣费比例%(创建时有效值,已钳0..100,S7;历史行 NULL=fallback)' AFTER cancel_free_hours_snapshot;

-- 自检：两列就位且均可空（期望 k=V14 apt cancel policy snapshot, n=2, nullable_cnt=2）
SELECT 'V14 apt cancel policy snapshot' AS k,
       (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'apt_appointment'
           AND COLUMN_NAME IN ('cancel_free_hours_snapshot', 'cancel_penalty_rate_snapshot')) AS n,
       (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'apt_appointment'
           AND COLUMN_NAME IN ('cancel_free_hours_snapshot', 'cancel_penalty_rate_snapshot')
           AND IS_NULLABLE = 'YES') AS nullable_cnt;

-- 回滚（如必要；回滚后旧镜像即可再启动）：
-- ALTER TABLE apt_appointment DROP COLUMN cancel_penalty_rate_snapshot, DROP COLUMN cancel_free_hours_snapshot;
