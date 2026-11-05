-- V1__core_init_schema.sql
-- 37 表全量初始化（ADR-007）。禁改：后续变更一律增量 V 文件。
-- 提取自《第七阶段-数据库ER模型与DDL》§3；deleted 已按 P1 统一化修订为 BIGINT。
SET NAMES utf8mb4;

CREATE TABLE usr_user (
  id            BIGINT       NOT NULL COMMENT '雪花ID',
  phone         VARCHAR(20)  NOT NULL COMMENT '手机号,登录凭证;注销时脱敏释放',
  password_hash VARCHAR(100) NULL COMMENT '可空,验证码登录可不设密',
  nickname      VARCHAR(50)  NOT NULL DEFAULT '',
  avatar        VARCHAR(255) NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/DISABLED',
  last_login_at DATETIME(3)  NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted       BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_phone (phone)
) COMMENT='账号主体';

CREATE TABLE usr_customer_profile (
  id                    BIGINT      NOT NULL,
  user_id               BIGINT      NOT NULL,
  default_contact_phone VARCHAR(20) NULL,
  preferred_store_id    BIGINT      NULL COMMENT '跨模块ID,无FK',
  marketing_opt_in      TINYINT     NOT NULL DEFAULT 1,
  created_at            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted               BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user (user_id)
) COMMENT='客户档案';

CREATE TABLE usr_user_pet (
  id         BIGINT      NOT NULL,
  user_id    BIGINT      NOT NULL,
  pet_id     BIGINT      NOT NULL,
  relation   VARCHAR(16) NOT NULL DEFAULT 'OWNER' COMMENT 'OWNER/FAMILY',
  invited_by BIGINT      NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted    BIGINT      NOT NULL DEFAULT 0 COMMENT 'P1:删除时置为id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_pet (user_id, pet_id, deleted),
  KEY idx_pet (pet_id)
) COMMENT='用户-宠物共管关联';

CREATE TABLE pet_pet (
  id              BIGINT        NOT NULL,
  name            VARCHAR(50)   NOT NULL,
  species         VARCHAR(16)   NOT NULL COMMENT 'DOG/CAT/OTHER',
  breed           VARCHAR(50)   NULL,
  gender          VARCHAR(16)   NOT NULL DEFAULT 'UNKNOWN' COMMENT 'MALE/FEMALE/UNKNOWN',
  is_neutered     TINYINT       NOT NULL DEFAULT 0,
  birthday        DATE          NULL,
  birthday_approx TINYINT       NOT NULL DEFAULT 0 COMMENT '生日是否约估',
  weight_kg       DECIMAL(5,2)  NULL,
  size_category   VARCHAR(16)   NULL COMMENT 'SMALL/MEDIUM/LARGE,体重映射,笼位分配依据',
  avatar          VARCHAR(255)  NULL,
  remark          VARCHAR(500)  NULL,
  vaccine_summary VARCHAR(500)  NULL COMMENT '最近接种摘要,medical事件同步,容忍最终一致',
  status          VARCHAR(16)   NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/DECEASED/ARCHIVED',
  created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted         BIGINT        NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_status (status)
) COMMENT='宠物档案';

CREATE TABLE pet_health_info (
  id              BIGINT      NOT NULL,
  pet_id          BIGINT      NOT NULL,
  allergies       JSON        NULL COMMENT '过敏信息数组',
  chronic_diseases JSON       NULL COMMENT '疾病信息数组',
  special_notes   VARCHAR(500) NULL,
  updated_by_role VARCHAR(16) NULL COMMENT 'CUSTOMER/DOCTOR,ADR-001-H溯源',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted         BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pet (pet_id)
) COMMENT='宠物健康信息';

CREATE TABLE sto_store (
  id                 BIGINT        NOT NULL,
  name               VARCHAR(100)  NOT NULL,
  phone              VARCHAR(20)   NOT NULL,
  address            VARCHAR(255)  NOT NULL,
  latitude           DECIMAL(10,6) NULL,
  longitude          DECIMAL(10,6) NULL,
  business_start_time TIME         NOT NULL DEFAULT '09:00:00',
  business_end_time   TIME         NOT NULL DEFAULT '21:00:00',
  break_start_time    TIME         NULL COMMENT '休息时段起(ADR-005-1)',
  break_end_time      TIME         NULL,
  status             VARCHAR(16)   NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/SUSPENDED/CLOSED',
  store_type         VARCHAR(16)   NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/PLATFORM_HQ(ADR-001-A,C端查询排除)',
  announcement       VARCHAR(500)  NULL,
  created_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted            BIGINT        NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_type_status (store_type, status)
) COMMENT='门店';
CREATE TABLE emp_employee (
  id                  BIGINT      NOT NULL,
  user_id             BIGINT      NOT NULL,
  employee_no         VARCHAR(32) NULL,
  name                VARCHAR(50) NOT NULL,
  status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ON_LEAVE/SUSPENDED/RESIGNED',
  hired_at            DATE        NULL,
  left_at             DATE        NULL,
  source_application_id BIGINT    NULL COMMENT '招聘溯源',
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted             BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user (user_id),
  KEY idx_status (status)
) COMMENT='员工身份';

CREATE TABLE emp_employee_store (
  id          BIGINT      NOT NULL,
  employee_id BIGINT      NOT NULL,
  store_id    BIGINT      NOT NULL,
  is_primary  TINYINT     NOT NULL DEFAULT 0,
  status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ENDED',
  started_at  DATE        NOT NULL,
  ended_at    DATE        NULL,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted     BIGINT      NOT NULL DEFAULT 0 COMMENT 'P1',
  PRIMARY KEY (id),
  UNIQUE KEY uk_emp_store (employee_id, store_id, deleted),
  KEY idx_store (store_id, status)
) COMMENT='员工-门店归属(ADR-004-2)';

CREATE TABLE emp_role (
  id         BIGINT      NOT NULL,
  code       VARCHAR(32) NOT NULL COMMENT '8角色枚举名',
  name       VARCHAR(50) NOT NULL,
  scope      VARCHAR(16) NOT NULL COMMENT 'PLATFORM/STORE',
  sort       INT         NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted    BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code)
) COMMENT='角色字典(种子固定)';

CREATE TABLE emp_role_permission (
  id              BIGINT      NOT NULL,
  role_id         BIGINT      NOT NULL,
  permission_code VARCHAR(64) NOT NULL COMMENT '=能力编码,如 appointment:assign',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_perm (role_id, permission_code)
) COMMENT='角色-权限映射(种子维护,无管理界面)';

CREATE TABLE emp_employee_role (
  id          BIGINT      NOT NULL,
  employee_id BIGINT      NOT NULL,
  role_id     BIGINT      NOT NULL,
  store_id    BIGINT      NOT NULL COMMENT '角色挂员工×门店(ADR-003-9)',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted     BIGINT      NOT NULL DEFAULT 0 COMMENT 'P1',
  PRIMARY KEY (id),
  UNIQUE KEY uk_emp_role_store (employee_id, role_id, store_id, deleted)
) COMMENT='员工角色关联';

CREATE TABLE emp_skill (
  id         BIGINT      NOT NULL,
  code       VARCHAR(32) NOT NULL COMMENT 'BATH/HAIRCUT/NAIL_CARE...',
  name       VARCHAR(50) NOT NULL,
  category   VARCHAR(32) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted    BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code)
) COMMENT='技能字典(ADMIN可维护)';

CREATE TABLE emp_employee_skill (
  id           BIGINT      NOT NULL,
  employee_id  BIGINT      NOT NULL,
  skill_id     BIGINT      NOT NULL,
  proficiency  VARCHAR(16) NULL COMMENT '预留',
  certified_at DATE        NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted      BIGINT      NOT NULL DEFAULT 0 COMMENT 'P1',
  PRIMARY KEY (id),
  UNIQUE KEY uk_emp_skill (employee_id, skill_id, deleted)
) COMMENT='员工技能关联';
CREATE TABLE cat_service (
  id                       BIGINT       NOT NULL,
  code                     VARCHAR(32)  NOT NULL COMMENT 'PET_BATH/DOG_WALK/...',
  name                     VARCHAR(50)  NOT NULL,
  description              VARCHAR(500) NULL,
  category                 VARCHAR(32)  NULL,
  required_duration_min    INT          NOT NULL COMMENT '标准时长,门店可覆盖',
  fulfillment_type         VARCHAR(16)  NOT NULL COMMENT 'INSTANT/MULTI_DAY(ADR-003-4)',
  allow_user_pick_employee TINYINT      NOT NULL DEFAULT 1,
  status                   VARCHAR(16)  NOT NULL DEFAULT 'ON' COMMENT 'ON/OFF',
  created_at               DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at               DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted                  BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code)
) COMMENT='平台服务目录';

CREATE TABLE cat_service_skill (
  id          BIGINT      NOT NULL,
  service_id  BIGINT      NOT NULL,
  skill_id    BIGINT      NOT NULL,
  is_required TINYINT     NOT NULL DEFAULT 1,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted     BIGINT      NOT NULL DEFAULT 0 COMMENT 'P1',
  PRIMARY KEY (id),
  UNIQUE KEY uk_service_skill (service_id, skill_id, deleted)
) COMMENT='服务所需技能声明';

CREATE TABLE cat_store_service (
  id                       BIGINT      NOT NULL,
  store_id                 BIGINT      NOT NULL,
  service_id               BIGINT      NOT NULL,
  price                    BIGINT      NOT NULL COMMENT '分',
  duration_min             INT         NOT NULL COMMENT '必须为30的倍数(ADR-005-2)',
  required_resource_type   VARCHAR(32) NULL COMMENT 'BATH_STATION/EXAM_ROOM/KENNEL_SMALL...,空=无需资源',
  allow_user_pick_employee TINYINT     NULL COMMENT 'NULL=继承服务默认',
  cancel_free_hours        INT         NOT NULL DEFAULT 24 COMMENT '免取消截止小时(ADR-005-12)',
  cancel_penalty_rate      INT         NOT NULL DEFAULT 0 COMMENT '扣费百分比,初期0',
  status                   VARCHAR(16) NOT NULL DEFAULT 'ON' COMMENT 'ON/OFF',
  created_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted                  BIGINT      NOT NULL DEFAULT 0 COMMENT 'P1:下架再上架场景',
  PRIMARY KEY (id),
  UNIQUE KEY uk_store_service (store_id, service_id, deleted),
  KEY idx_store_status (store_id, status)
) COMMENT='门店上架服务(关系实体一等公民)';

CREATE TABLE sch_schedule (
  id           BIGINT       NOT NULL,
  employee_id  BIGINT       NOT NULL,
  store_id     BIGINT       NOT NULL COMMENT '须∈员工ACTIVE归属(Service层校验)',
  work_date    DATE         NOT NULL,
  start_time   TIME         NOT NULL,
  end_time     TIME         NOT NULL,
  status       VARCHAR(16)  NOT NULL DEFAULT 'WORKING' COMMENT 'WORKING/LEAVE',
  leave_reason VARCHAR(200) NULL,
  leave_status VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE/APPLYING/APPROVED',
  source       VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/BATCH',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_emp_date (employee_id, work_date) COMMENT '一日一班+同日单店(ADR-004/005),兼作员工冲突FOR UPDATE锁点',
  KEY idx_store_date (store_id, work_date)
) COMMENT='员工按天班次(P2:物理删除/覆盖更新)';

CREATE TABLE sch_resource (
  id         BIGINT      NOT NULL,
  store_id   BIGINT      NOT NULL,
  type       VARCHAR(32) NOT NULL COMMENT 'BATH_STATION/GROOMING_STATION/EXAM_ROOM/KENNEL_SMALL/KENNEL_MEDIUM/KENNEL_LARGE/CAT_ROOM',
  name       VARCHAR(50) NOT NULL,
  status     VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE/MAINTENANCE/DISABLED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted    BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_store_type (store_id, type, status)
) COMMENT='门店资源';

CREATE TABLE sch_resource_schedule (
  id             BIGINT      NOT NULL,
  resource_id    BIGINT      NOT NULL,
  work_date      DATE        NOT NULL,
  slot_no        SMALLINT    NOT NULL COMMENT '0..47=30分钟槽;99=天级(笼位)',
  appointment_id BIGINT      NOT NULL,
  occupy_type    VARCHAR(16) NOT NULL COMMENT 'TIME_SLOT/DAY',
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_occupy (resource_id, work_date, slot_no) COMMENT '防超卖最后防线(ADR-005-2)',
  KEY idx_appt (appointment_id),
  KEY idx_date_type (work_date, occupy_type)
) COMMENT='资源槽位占用(P2:释放=物理删行,迁移=同事务删旧插新)';
CREATE TABLE apt_appointment (
  id                     BIGINT       NOT NULL,
  appointment_no         VARCHAR(32)  NOT NULL COMMENT 'AP+yyyyMMdd+序列',
  user_id                BIGINT       NOT NULL,
  pet_id                 BIGINT       NOT NULL,
  store_id               BIGINT       NOT NULL,
  store_service_id       BIGINT       NOT NULL,
  employee_id            BIGINT       NULL COMMENT '可空,店长后补(ADR-005-6)',
  order_id               BIGINT       NULL COMMENT '1:1回填',
  service_date           DATE         NOT NULL,
  start_time             TIME         NULL COMMENT 'INSTANT型',
  end_time               TIME         NULL,
  boarding_checkin_date  DATE         NULL COMMENT 'MULTI_DAY型',
  boarding_checkout_date DATE         NULL,
  service_name_snapshot  VARCHAR(100) NOT NULL,
  price_snapshot         BIGINT       NOT NULL COMMENT '分',
  duration_snapshot      INT          NOT NULL,
  status                 VARCHAR(32)  NOT NULL DEFAULT 'PENDING_PAYMENT'
                         COMMENT 'PENDING_PAYMENT/CONFIRMED/WAIT_SERVICE/SERVING/COMPLETED/CANCELLED',
  cancelled_reason       VARCHAR(200) NULL,
  cancel_by_role         VARCHAR(32)  NULL,
  remark                 VARCHAR(500) NULL,
  expire_at              DATETIME(3)  NOT NULL COMMENT '支付截止=创建+15min(ADR-005-9)',
  source                 VARCHAR(16)  NOT NULL DEFAULT 'CUSTOMER' COMMENT 'CUSTOMER/RECEPTIONIST',
  actual_start_at        DATETIME(3)  NULL,
  actual_end_at          DATETIME(3)  NULL,
  version                INT          NOT NULL DEFAULT 0,
  created_at             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted                BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_no (appointment_no),
  KEY idx_store_date (store_id, service_date),
  KEY idx_emp_date (employee_id, service_date),
  KEY idx_user_status (user_id, status),
  KEY idx_status_expire (status, expire_at) COMMENT '超时扫描'
) COMMENT='预约(聚合根,状态机见ADR-005-11)';

CREATE TABLE ord_order (
  id                  BIGINT      NOT NULL,
  order_no            VARCHAR(32) NOT NULL,
  appointment_id      BIGINT      NOT NULL,
  user_id             BIGINT      NOT NULL,
  store_id            BIGINT      NOT NULL,
  amount              BIGINT      NOT NULL COMMENT '分,创建后不可变(ADR-006-9)',
  discount_amount     BIGINT      NOT NULL DEFAULT 0 COMMENT '预留恒0',
  paid_amount         BIGINT      NOT NULL DEFAULT 0,
  status              VARCHAR(32) NOT NULL DEFAULT 'WAIT_PAY'
                      COMMENT 'WAIT_PAY/PAID/WAIT_SERVICE/SERVING/COMPLETED/CANCELLED/REFUNDING/REFUNDED(PART_REFUNDED预留)',
  pay_expire_at       DATETIME(3) NOT NULL,
  paid_at             DATETIME(3) NULL,
  payment_method      VARCHAR(16) NULL COMMENT 'ONLINE/OFFLINE(ADR-006-6)',
  offline_confirmed_by BIGINT     NULL COMMENT '线下收款复核店长',
  paid_then_cancelled TINYINT     NOT NULL DEFAULT 0 COMMENT 'ADR-006-5标记',
  item_snapshot       JSON        NOT NULL COMMENT '服务名/时长/单价,详情自足(ADR-002-8)',
  version             INT         NOT NULL DEFAULT 0,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted             BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_no (order_no),
  UNIQUE KEY uk_appointment (appointment_id) COMMENT '1:1(ADR-003-6)',
  KEY idx_user_status (user_id, status),
  KEY idx_store_status_time (store_id, status, created_at),
  KEY idx_status_expire (status, pay_expire_at) COMMENT '超时扫描'
) COMMENT='交易单(聚合根)';

CREATE TABLE ord_refund (
  id              BIGINT       NOT NULL,
  refund_no       VARCHAR(32)  NOT NULL,
  order_id        BIGINT       NOT NULL,
  amount          BIGINT       NOT NULL COMMENT '分,初期恒=实付(全额退)',
  reason          VARCHAR(200) NOT NULL,
  status          VARCHAR(32)  NOT NULL DEFAULT 'APPLYING'
                  COMMENT 'APPLYING/APPROVED/REJECTED/EXECUTING/SUCCESS/FAILED',
  apply_by        VARCHAR(16)  NOT NULL COMMENT 'USER/STORE/ADMIN/SYSTEM',
  approve_role    VARCHAR(32)  NULL COMMENT 'STORE_MANAGER/ADMIN(分级审批ADR-001-B)',
  approver_id     BIGINT       NULL,
  manual_override TINYINT      NOT NULL DEFAULT 0 COMMENT 'ADMIN兜底通道(ADR-006-1)',
  refunded_at     DATETIME(3)  NULL,
  version         INT          NOT NULL DEFAULT 0,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted         BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_no (refund_no),
  KEY idx_order (order_id),
  KEY idx_status (status)
) COMMENT='退款业务单';

CREATE TABLE ord_recon_diff (
  id          BIGINT       NOT NULL,
  recon_date  DATE         NOT NULL,
  order_id    BIGINT       NOT NULL,
  payment_id  BIGINT       NULL,
  diff_type   VARCHAR(32)  NOT NULL COMMENT 'AMOUNT_MISMATCH/ORDER_MISSING/TXN_MISSING/STATUS_DIVERGE',
  diff_desc   VARCHAR(500) NULL,
  status      VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/RESOLVED',
  resolved_by BIGINT       NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted     BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_date_status (recon_date, status)
) COMMENT='日对账差异(ADR-006-8)';

CREATE TABLE pay_payment (
  id                BIGINT      NOT NULL,
  payment_no        VARCHAR(32) NOT NULL COMMENT '=渠道out_trade_no',
  order_id          BIGINT      NOT NULL,
  channel           VARCHAR(16) NOT NULL COMMENT 'WECHAT/ALIPAY(预留)/OFFLINE',
  channel_txn_no    VARCHAR(64) NULL COMMENT '渠道交易号,回调后回填',
  channel_prepay_id VARCHAR(64) NULL,
  amount            BIGINT      NOT NULL,
  status            VARCHAR(16) NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING/SUCCESS/FAILED/CLOSED',
  amount_mismatch   TINYINT     NOT NULL DEFAULT 0 COMMENT 'ADR-006-3告警标记',
  callback_raw      TEXT        NULL COMMENT '回调原文,审计对账',
  paid_at           DATETIME(3) NULL,
  version           INT         NOT NULL DEFAULT 0,
  created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted           BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_no (payment_no),
  UNIQUE KEY uk_channel_txn (channel_txn_no) COMMENT '回调幂等防线1(NULL不冲突,允许多条PROCESSING)',
  KEY idx_order (order_id),
  KEY idx_status_time (status, created_at) COMMENT 'PROCESSING清理任务'
) COMMENT='支付流水(SUCCESS至多一条/订单,Service层复核)';

CREATE TABLE pay_refund_txn (
  id                BIGINT      NOT NULL,
  refund_id         BIGINT      NOT NULL,
  channel           VARCHAR(16) NOT NULL,
  channel_refund_no VARCHAR(64) NULL,
  amount            BIGINT      NOT NULL,
  status            VARCHAR(16) NOT NULL DEFAULT 'EXECUTING' COMMENT 'EXECUTING/SUCCESS/FAILED',
  retry_count       INT         NOT NULL DEFAULT 0,
  callback_raw      TEXT        NULL,
  created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted           BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_channel_refund (channel_refund_no),
  KEY idx_refund (refund_id)
) COMMENT='退款渠道流水';
CREATE TABLE ful_bath_record (
  id              BIGINT       NOT NULL,
  appointment_id  BIGINT       NOT NULL,
  pet_id          BIGINT       NOT NULL,
  employee_id     BIGINT       NOT NULL COMMENT '实际执行人',
  store_service_id BIGINT      NOT NULL,
  executor_role   VARCHAR(32)  NOT NULL COMMENT '执行人当时角色',
  by_manager      TINYINT      NOT NULL DEFAULT 0 COMMENT '店长顶班(ADR-001-G)',
  started_at      DATETIME(3)  NOT NULL,
  finished_at     DATETIME(3)  NOT NULL,
  service_items   JSON         NULL COMMENT '实际执行项目',
  photos          JSON         NULL COMMENT '对象存储key数组',
  remark          VARCHAR(500) NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted         BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_appt (appointment_id),
  KEY idx_pet (pet_id),
  KEY idx_emp_time (employee_id, finished_at)
) COMMENT='洗澡/美容服务记录';

CREATE TABLE ful_walk_record (
  id             BIGINT       NOT NULL,
  appointment_id BIGINT       NOT NULL,
  pet_id         BIGINT       NOT NULL,
  employee_id    BIGINT       NOT NULL,
  executor_role  VARCHAR(32)  NOT NULL,
  by_manager     TINYINT      NOT NULL DEFAULT 0,
  started_at     DATETIME(3)  NOT NULL,
  finished_at    DATETIME(3)  NOT NULL,
  duration_min   INT          NOT NULL,
  distance_m     INT          NULL,
  track_summary  JSON         NULL,
  photos         JSON         NULL,
  poop_count     INT          NULL COMMENT '排便记录',
  remark         VARCHAR(500) NULL,
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted        BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_appt (appointment_id),
  KEY idx_pet (pet_id),
  KEY idx_emp_time (employee_id, finished_at)
) COMMENT='遛狗服务记录';

CREATE TABLE med_medical_record (
  id             BIGINT       NOT NULL,
  appointment_id BIGINT       NULL COMMENT '可空:walk-in急诊(ADR-003)',
  pet_id         BIGINT       NOT NULL,
  doctor_id      BIGINT       NOT NULL,
  store_id       BIGINT       NOT NULL,
  visit_time     DATETIME(3)  NOT NULL,
  symptoms       TEXT         NULL,
  diagnosis      TEXT         NULL,
  treatment      TEXT         NULL,
  prescription   JSON         NULL COMMENT '药品/剂量/频次/天数',
  advice         VARCHAR(500) NULL,
  fee_items      JSON         NULL COMMENT '诊疗费明细',
  status         VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/SIGNED',
  signed_at      DATETIME(3)  NULL COMMENT '72h修改窗起点(ADR-001-C)',
  edit_reason    VARCHAR(200) NULL COMMENT '窗口内修改原因必填',
  version        INT          NOT NULL DEFAULT 0,
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted        BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_appt (appointment_id),
  KEY idx_pet_time (pet_id, visit_time),
  KEY idx_doctor (doctor_id, visit_time),
  KEY idx_store (store_id)
) COMMENT='诊疗记录';

CREATE TABLE med_medical_supplement (
  id                BIGINT      NOT NULL,
  medical_record_id BIGINT      NOT NULL,
  doctor_id         BIGINT      NOT NULL,
  content           TEXT        NOT NULL,
  created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_record (medical_record_id)
) COMMENT='诊疗补充(P3:append-only,不可改删)';

CREATE TABLE med_vaccination_record (
  id                BIGINT      NOT NULL,
  pet_id            BIGINT      NOT NULL,
  vaccine_name      VARCHAR(100) NOT NULL,
  vaccine_brand     VARCHAR(100) NULL,
  batch_no          VARCHAR(50) NULL,
  injected_at       DATE        NOT NULL,
  next_due_date     DATE        NULL COMMENT '提醒数据源',
  doctor_id         BIGINT      NULL,
  medical_record_id BIGINT      NULL COMMENT '独立接种可空',
  created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted           BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_pet (pet_id),
  KEY idx_due (next_due_date) COMMENT 'VACCINE_DUE提醒扫描'
) COMMENT='疫苗记录';

CREATE TABLE med_report (
  id                BIGINT       NOT NULL,
  medical_record_id BIGINT       NOT NULL,
  pet_id            BIGINT       NOT NULL,
  type              VARCHAR(16)  NOT NULL COMMENT 'IMAGE/LAB_REPORT/OTHER',
  file_key          VARCHAR(255) NOT NULL COMMENT '对象存储key',
  file_name         VARCHAR(100) NULL,
  uploaded_by       BIGINT       NOT NULL,
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted           BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_record (medical_record_id),
  KEY idx_pet (pet_id)
) COMMENT='检查报告/影像';

CREATE TABLE boa_boarding_stay (
  id                   BIGINT       NOT NULL,
  appointment_id       BIGINT       NOT NULL,
  pet_id               BIGINT       NOT NULL,
  store_id             BIGINT       NOT NULL,
  checkin_date         DATE         NOT NULL,
  checkout_date        DATE         NOT NULL,
  actual_checkin_at    DATETIME(3)  NULL,
  actual_checkout_at   DATETIME(3)  NULL,
  kennel_resource_id   BIGINT       NULL COMMENT '实际笼位(入住可迁移,ADR-005-7)',
  status               VARCHAR(16)  NOT NULL DEFAULT 'EXPECTED'
                       COMMENT 'EXPECTED/CHECKED_IN/DAILY_CARE/CHECKED_OUT/ABNORMAL',
  caretaker_id         BIGINT       NULL COMMENT '主责护理员,入住时指定(ADR-005-6)',
  special_instructions VARCHAR(500) NULL,
  version              INT          NOT NULL DEFAULT 0,
  created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted              BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_appt (appointment_id),
  KEY idx_store_status (store_id, status),
  KEY idx_pet (pet_id)
) COMMENT='托管入住单(聚合根)';

CREATE TABLE boa_daily_care_record (
  id               BIGINT       NOT NULL,
  boarding_stay_id BIGINT       NOT NULL,
  care_date        DATE         NOT NULL,
  caretaker_id     BIGINT       NOT NULL,
  feeding          JSON         NULL COMMENT '次数/食量',
  watering         JSON         NULL,
  walk             JSON         NULL COMMENT '次数/时长',
  observation      VARCHAR(500) NULL COMMENT '精神/排泄/食欲',
  photos           JSON         NULL,
  abnormal_flag    TINYINT      NOT NULL DEFAULT 0,
  abnormal_desc    VARCHAR(500) NULL COMMENT '异常上报→通知店长与客户',
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted          BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_stay_date (boarding_stay_id, care_date)
) COMMENT='每日护理记录(每天≥1条,不建唯一约束允许多条)';
CREATE TABLE rec_recruitment (
  id           BIGINT       NOT NULL,
  store_id     BIGINT       NOT NULL,
  title        VARCHAR(100) NOT NULL,
  role_target  VARCHAR(32)  NOT NULL COMMENT '目标员工角色code',
  salary_range VARCHAR(50)  NULL,
  description  TEXT         NULL,
  requirement  TEXT         NULL,
  status       VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/PAUSED/CLOSED',
  published_by BIGINT       NOT NULL,
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted      BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_store_status (store_id, status)
) COMMENT='招聘岗位';

CREATE TABLE rec_application (
  id                BIGINT       NOT NULL,
  recruitment_id    BIGINT       NOT NULL,
  user_id           BIGINT       NOT NULL,
  resume_snapshot   JSON         NOT NULL COMMENT '快照防资料漂移',
  attachment_key    VARCHAR(255) NULL,
  status            VARCHAR(16)  NOT NULL DEFAULT 'SUBMITTED'
                    COMMENT 'SUBMITTED/VIEWED/INTERVIEW/HIRED/REJECTED',
  status_log        JSON         NULL,
  reject_reason     VARCHAR(200) NULL,
  hired_employee_id BIGINT       NULL COMMENT '录用溯源闭环',
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted           BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_rec_status (recruitment_id, status),
  KEY idx_user (user_id)
) COMMENT='投递';

CREATE TABLE ntf_notification (
  id         BIGINT       NOT NULL,
  user_id    BIGINT       NOT NULL,
  type       VARCHAR(32)  NOT NULL COMMENT 'APPOINTMENT_PAID/SERVICE_REMIND/MEDICAL_DONE/CARE_ABNORMAL/RECRUIT_HIRED/VACCINE_DUE...',
  title      VARCHAR(100) NOT NULL,
  content    VARCHAR(500) NOT NULL,
  biz_ref    JSON         NULL COMMENT '{module,bizId}弱引用,点击跳转',
  read_at    DATETIME(3)  NULL,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_user_read (user_id, read_at, created_at)
) COMMENT='站内通知(P3:仅增+已读标记)';

CREATE TABLE sys_config (
  id           BIGINT       NOT NULL,
  config_key   VARCHAR(64)  NOT NULL,
  config_value VARCHAR(255) NOT NULL,
  remark       VARCHAR(200) NULL,
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_key (config_key)
) COMMENT='平台配置(key-value,app模块Owner)';
