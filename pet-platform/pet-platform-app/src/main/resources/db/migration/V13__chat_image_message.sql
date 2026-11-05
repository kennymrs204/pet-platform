-- ============================================================================
-- V13：聊天图片消息（P7.1）——msg_message 扩展媒体列
--
-- 范围：仅 ALTER msg_message 加 4 列 + 自检。**不改任何既有列/约束/索引**，
--   不新增权限码（复用 chat:send / store:chat:send）——无 Redis 缓存需清理
--   （与 V8/V10/V12 的授权迁移不同，本迁移无需 DEL perm:*）。
-- 语义（P7 预研报告 E 节既有定义，逐字段实施）：
--   message_type  TEXT（默认，历史行与旧写入路径零回填即兼容）/ IMAGE（图片消息）；
--   media_key     仅 IMAGE：对象存储 key（u-{uid}/CHAT_IMAGE/{yyyyMM}/{雪花}.{ext}）——
--                 **绝不存 URL**（presigned 由读侧业务出口每次动态签发）；
--   media_content_type 仅 IMAGE：服务端由 key 扩展名反推（jpg→image/jpeg…）；
--   media_size    仅 IMAGE：P7.1 暂不落值（无发送时 HEAD 校验，不假造大小；P7.2 回填）。
--   IMAGE 的 content 固定 '[图片]'（服务端校验）——摘要/旧客户端/列表预览零改动。
-- append-only 语义不变（无删除/编辑，无 deleted 列）；uk_conv_client 幂等链不变。
--
-- ⚠ 部署注意：V13 应用后，**只含 V1~V12 的旧镜像不可再启动**（Flyway
--   validate-on-migrate 会因库版本高于本地最新迁移而失败）——须用含 V13 的新镜像重建。
-- ============================================================================

ALTER TABLE msg_message
  ADD COLUMN message_type VARCHAR(16) NOT NULL DEFAULT 'TEXT'
      COMMENT '消息类型：TEXT（默认，历史行同）/IMAGE（media_key 引用对象存储）' AFTER content,
  ADD COLUMN media_key VARCHAR(255) NULL
      COMMENT '对象存储 key（仅 IMAGE：u-{uid}/CHAT_IMAGE/{yyyyMM}/{雪花}.{ext}）——不存 URL' AFTER message_type,
  ADD COLUMN media_content_type VARCHAR(64) NULL
      COMMENT '媒体 MIME（仅 IMAGE；服务端由 key 扩展名反推）' AFTER media_key,
  ADD COLUMN media_size BIGINT NULL
      COMMENT '媒体字节数（仅 IMAGE；P7.1 暂不落值，P7.2 发送时 HEAD 校验后回填）' AFTER media_content_type;

-- 自检：四列就位，且 message_type 对历史行默认 TEXT（本 SELECT 是迁移的一部分，
-- 期望 k=V13 msg_message media columns, n=4）
SELECT 'V13 msg_message media columns' AS k,
       (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'msg_message'
           AND COLUMN_NAME IN ('message_type', 'media_key', 'media_content_type', 'media_size')) AS n,
       (SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'msg_message'
           AND COLUMN_NAME = 'message_type') AS message_type_default;

-- 回滚（如必要；回滚后旧镜像即可再启动）：
-- ALTER TABLE msg_message DROP COLUMN media_size, DROP COLUMN media_content_type,
--   DROP COLUMN media_key, DROP COLUMN message_type;
