-- ============================================================================
-- V11：门店-客户双向沟通（会话 + 消息）基础表与权限种子（C/B 聊天 P2）
--
-- 范围：仅建两张表 + 四个权限码。**不含任何通知链路改动**——聊天正文独立存
--   msg_message，不写 ntf_notification（产品决策：消息与系统通知职责分离）。
-- 口径（已拍板）：
--   会话 = (store_id, customer_user_id) 长期唯一（uk 是并发 get-or-create 的最终裁判，
--   不依赖"先查再插"的单点判断）；不同门店严格隔离（store_id 进 uk 与所有查询条件）。
--   未读 = **游标式**（customer/store_last_read_message_id），刻意不存计数器——
--   计数器方案在"并发发送 × 标记已读"下需要额外锁或原子序，游标快照天然无丢失面：
--   标记已读写"当刻会话最大消息 id"快照，其后到达的消息 id 更大，必然保持未读。
--   消息 append-only（无删除/编辑；表无 deleted 列，同 ntf_notification 先例）。
-- 幂等：NOT EXISTS 逐码判重；只 INSERT/建表，不 UPDATE/DELETE 任何已应用迁移的行。
-- 主键续号：权限行必须 MAX(id)+1 起（V2~V10 已占若干行，不能猜）。
--
-- ⚠ 部署注意（V8/V10 同类坑）：权限码集缓存在 perm:{userId}:{storeCtx}，加码不失效旧缓存——
--   上线后执行：docker exec pet-platform-redis redis-cli -a <pwd> --scan --pattern 'perm:*' | xargs -r redis-cli DEL
--   或让相关账号重新登录。
-- ============================================================================

CREATE TABLE msg_conversation (
  id                            BIGINT       NOT NULL COMMENT '雪花 ID',
  store_id                      BIGINT       NOT NULL COMMENT '门店（跨模块ID，无FK）',
  customer_user_id              BIGINT       NOT NULL COMMENT '客户账号（usr_user.id，跨模块ID）',
  origin_biz_module             VARCHAR(32)  NULL COMMENT '首建来源业务类型（弱引用，如 appointment）——创建后不再变更',
  origin_biz_id                 BIGINT       NULL COMMENT '首建来源业务单 id——创建后不再变更；≠"当前讨论的订单"',
  last_message_id               BIGINT       NULL COMMENT '最近一条消息 id（派生缓存，与消息写入同事务维护）',
  last_message_at               DATETIME(3)  NULL COMMENT '最近消息时间（列表排序键）',
  last_message_preview          VARCHAR(100) NULL COMMENT '最近消息摘要（截断后落库，不只在内存拼）',
  customer_last_read_message_id BIGINT       NULL COMMENT '客户已读游标：客户侧未读 = 对侧发送 AND id > 本值',
  store_last_read_message_id    BIGINT       NULL COMMENT '门店已读游标（门店级共享已读）：门店侧未读 = 客户发送 AND id > 本值',
  created_at                    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at                    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_store_customer (store_id, customer_user_id)
      COMMENT '同一门店×同一客户唯一长期会话（并发 get-or-create 最终裁判）',
  KEY idx_store_last (store_id, last_message_at)
      COMMENT 'B 端本店会话列表（ORDER BY last_message_at DESC, id DESC）',
  KEY idx_customer_last (customer_user_id, last_message_at)
      COMMENT 'C 端我的会话列表'
) COMMENT '门店-客户长期会话（第一版：无归档/关闭；不因订单多开）';

CREATE TABLE msg_message (
  id                   BIGINT       NOT NULL COMMENT '雪花 ID——插入序即排序键：游标分页与排序同键，同毫秒也不重不漏',
  conversation_id      BIGINT       NOT NULL COMMENT '会话（msg_conversation.id）',
  sender_type          VARCHAR(8)   NOT NULL COMMENT 'CUSTOMER/STORE',
  sender_user_id       BIGINT       NOT NULL COMMENT '发送方 usr_user.id（服务端按鉴权身份写入，不信任入参）',
  sender_name_snapshot VARCHAR(50)  NOT NULL COMMENT '发送时姓名快照（防改名/离职后显示丢失）',
  content              VARCHAR(500) NOT NULL COMMENT '纯文本（服务端 trim 后非空且 ≤500）',
  biz_module           VARCHAR(32)  NULL COMMENT '可选业务关联（弱引用，如 appointment）',
  biz_id               BIGINT       NULL COMMENT '可选业务单 id',
  client_msg_id        VARCHAR(64)  NULL COMMENT '客户端幂等键（同会话内唯一；服务端强制必传）',
  created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_conv_client (conversation_id, client_msg_id)
      COMMENT '发送幂等：同会话同 clientMsgId 唯一（NULL 可多行，服务端校验必传）',
  KEY idx_conv_id (conversation_id, id)
      COMMENT '会话内按 id 游标分页主路径（beforeId 前缀命中）'
) COMMENT '聊天消息（append-only：无删除/编辑，无 deleted 列）';

-- ============================ 权限种子 ======================================

SET @i := (SELECT COALESCE(MAX(id), 0) FROM emp_role_permission);

-- C 端客户：chat:view / chat:send（仅 CUSTOMER；storeCtx=0 上下文装配）
INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT @i := @i + 1, r.id, x.code
FROM emp_role r
JOIN (SELECT 'chat:view' AS code UNION ALL SELECT 'chat:send') x
WHERE r.code = 'CUSTOMER' AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM emp_role_permission p
                  WHERE p.role_id = r.id AND p.permission_code = x.code);

-- B 端门店沟通：store:chat:view / store:chat:send → STORE_MANAGER + RECEPTIONIST
-- （客服/接待角色；其他员工角色不因本功能自动获得。ADMIN 经 PermissionAssembler 放大机制自动生效）
INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT @i := @i + 1, r.id, x.code
FROM emp_role r
JOIN (SELECT 'store:chat:view' AS code UNION ALL SELECT 'store:chat:send') x
WHERE r.code IN ('STORE_MANAGER', 'RECEPTIONIST') AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM emp_role_permission p
                  WHERE p.role_id = r.id AND p.permission_code = x.code);

-- 自检：三行各期望 2
SELECT 'CUSTOMER 持 chat:view+send' AS k, COUNT(*) AS n
  FROM emp_role_permission rp JOIN emp_role r ON r.id = rp.role_id
 WHERE r.code = 'CUSTOMER' AND rp.permission_code IN ('chat:view', 'chat:send')
UNION ALL
SELECT 'STORE_MANAGER 持 store:chat:view+send', COUNT(*)
  FROM emp_role_permission rp JOIN emp_role r ON r.id = rp.role_id
 WHERE r.code = 'STORE_MANAGER' AND rp.permission_code IN ('store:chat:view', 'store:chat:send')
UNION ALL
SELECT 'RECEPTIONIST 持 store:chat:view+send', COUNT(*)
  FROM emp_role_permission rp JOIN emp_role r ON r.id = rp.role_id
 WHERE r.code = 'RECEPTIONIST' AND rp.permission_code IN ('store:chat:view', 'store:chat:send');

-- ============================== 回滚 =========================================
-- DROP TABLE IF EXISTS msg_message;
-- DROP TABLE IF EXISTS msg_conversation;
-- DELETE rp FROM emp_role_permission rp JOIN emp_role r ON r.id = rp.role_id
--  WHERE rp.permission_code IN ('chat:view','chat:send','store:chat:view','store:chat:send');
-- 回滚后必须清 Redis：DEL perm:*（见上方部署注意），否则已登录账号持旧码集到 TTL。
