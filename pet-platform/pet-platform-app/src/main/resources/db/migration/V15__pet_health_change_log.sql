-- ============================================================================
-- V15：宠物健康档案变更日志（append-only）
--
-- 为什么需要新表：pet_health_info 只保留当前快照（updated_at/updated_by_role），
--   项目内没有通用审计/历史表，无法还原旧值。本表只记录**实际发生的写入**，
--   不回填、不把当前值伪装成历史。
-- 语义：
--   before_json/after_json 为三字段快照（allergies/chronicDiseases/specialNotes）；
--   changed_fields 为本次实际变化的字段名数组；无实际变化不落行。
--   操作人身份由服务端从登录主体/门店上下文产生，客户端不可传。
--   保留规则：随宠物存续保留；宠物逻辑删除不级联删除日志，本轮无自动清理任务。
-- 权限：写入复用 pet:health:manage，历史读取复用 pet:health:view；无新权限码。
-- 并发控制：本迁移不新增 version 列。健康编辑复用 pet_health_info.updated_at（DATETIME(3)）
--   作为版本令牌，并在同一事务内 SELECT ... FOR UPDATE 行锁；PetHealthMapper.updateHealthSnapshot
--   显式把 updated_at 推进至少 1ms，避免同毫秒更新得到相同令牌。日志与健康行同事务提交。
--
-- ⚠ 部署注意：本迁移必须先于新镜像启动应用。V15 应用后，只含 V1~V14 的旧镜像
--   与库版本不一致；按当前 validate-on-migrate:true 及项目 V14 迁移的既有注释，
--   存在 Flyway 校验失败风险，部署前必须用「旧镜像 + 已应用 V15 的库」实测确认，禁止仅凭假设定论。
-- ============================================================================

CREATE TABLE pet_health_change_log (
  id              BIGINT       NOT NULL,
  pet_id          BIGINT       NOT NULL,
  store_id        BIGINT       NULL COMMENT 'B端门店上下文；C端自助修改为NULL',
  operator_type   VARCHAR(16)  NOT NULL COMMENT 'CUSTOMER/EMPLOYEE/ADMIN',
  operator_id     BIGINT       NOT NULL COMMENT '员工id（EMPLOYEE）或用户id（CUSTOMER/ADMIN）',
  operator_name   VARCHAR(100) NULL COMMENT '写入时姓名快照，查无时为空',
  operator_role   VARCHAR(32)  NULL COMMENT '写入时主角色码：CUSTOMER/DOCTOR/CARETAKER/ADMIN/…',
  changed_fields  JSON         NOT NULL COMMENT '本次实际变更字段名数组',
  before_json     JSON         NOT NULL COMMENT '变更前三字段快照',
  after_json      JSON         NOT NULL COMMENT '变更后三字段快照',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_pet_created (pet_id, created_at, id)
) COMMENT='宠物健康档案变更日志（append-only）';

-- 自检：表存在且关键列齐全（期望 k=V15 pet health change log, n=11）
SELECT 'V15 pet health change log' AS k,
       (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pet_health_change_log') AS n;

-- 回滚（如必要；回滚后旧镜像即可再启动）：
-- DROP TABLE pet_health_change_log;
