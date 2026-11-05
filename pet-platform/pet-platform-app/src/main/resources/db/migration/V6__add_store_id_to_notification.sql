-- ============================================================================
-- V6：站内通知补门店维度（ADR-047 §1–§2）
-- 给 ntf_notification 加 store_id 列 + (user_id, store_id, created_at) 索引。不建表、不改 V1~V5。
--
-- 为什么加列而不是新建"门店消息表 + 已读表"：B 端收件箱要的仍是"发给某个人的通知"，
-- 门店级"待处理"的真相本来就长在 ord_refund（WHERE status='APPLYING'），若再让消息表存一份
-- "店处理了没有"就是第二个真相（ADR-041 §5 反对的正是这个）。加一列只回答一个问题：
-- **这条消息是在哪个门店上下文里产生的**，从而让多店任职的员工（实测 DemoManager 同属 9111/9112）
-- 切店时红点跟着店走，而不是把两店消息糊成一个数。
--
-- 为什么 NOT NULL DEFAULT 0 而不是 NULL：0 是本仓库既有的"无门店上下文"哨兵
-- （AuthPrincipal.storeCtx=0 表示 C 端/平台级），沿用它可让读侧写 `store_id = #{storeId}` 而不是
-- `IS NULL` 的三值逻辑。ALTER 时历史行自动落 0 ⇒ **不进任何门店收件箱**：
-- 实测迁移前的 16 行 biz_ref.module='refund' 是 ADR-041 订正前旧镜像写的脏语义，
-- 且店长那 5 条 legacy 未读里只有 2 条对应退款还在 APPLYING——旧行不回填、不猜，直接挡在收件箱外。
--
-- C 端不受影响：CustomerNotificationController 走的 pageForUser/unreadCount 完全不提 store_id，
-- 本片只新增按门店过滤的读方法（旧方法一字不动）。
-- ============================================================================

ALTER TABLE ntf_notification
  ADD COLUMN store_id BIGINT NOT NULL DEFAULT 0
      COMMENT '收件人所属门店上下文；0=非门店消息（C 端个人消息与迁移前历史行）' AFTER user_id,
  ADD KEY idx_user_store_created (user_id, store_id, created_at);
