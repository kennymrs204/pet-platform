-- V2__seed_base_data.sql
-- 种子数据（ADR-007-6）。权限映射由《第一阶段-角色能力矩阵》生成，矩阵为唯一事实来源。
-- 说明：CUSTOMER 也入 emp_role（账号型角色）——权限装配时，存在 CustomerProfile 的登录用户
-- 自动并入 CUSTOMER 角色权限；该角色永不挂到 Employee 上（ADR-001-J 双身份模型）。

SET NAMES utf8mb4;

-- ========== 1. 平台总部虚拟门店（ADR-001-A） ==========
INSERT INTO sto_store (id, name, phone, address, status, store_type, announcement)
VALUES (1, '平台总部', '00000000000', '虚拟门店-平台管理', 'OPEN', 'PLATFORM_HQ', NULL);

-- ========== 2. 角色字典（8 角色，第一阶段矩阵 §1.1） ==========
INSERT INTO emp_role (id, code, name, scope, sort) VALUES
(101, 'CUSTOMER',      '宠物主人',     'PLATFORM', 1),
(102, 'ADMIN',         '平台管理员',   'PLATFORM', 2),
(103, 'STORE_MANAGER', '门店店长',     'STORE',    3),
(104, 'DOCTOR',        '宠物医生',     'STORE',    4),
(105, 'GROOMER',       '洗澡美容师',   'STORE',    5),
(106, 'DOG_WALKER',    '遛狗师',       'STORE',    6),
(107, 'CARETAKER',     '护理托管员',   'STORE',    7),
(108, 'RECEPTIONIST',  '前台',         'STORE',    8);

-- ========== 3. 角色-权限映射（能力编码 = permission_code） ==========
SET @i := 200000;

INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT (@i := @i + 1), r.id, c.code FROM emp_role r JOIN (
  SELECT 'account:profile:manage' AS code
  UNION ALL SELECT 'store:browse'            UNION ALL SELECT 'catalog:browse'
  UNION ALL SELECT 'availability:view'      UNION ALL SELECT 'ntf:receive'
  UNION ALL SELECT 'pet:create'             UNION ALL SELECT 'pet:update'
  UNION ALL SELECT 'pet:delete'             UNION ALL SELECT 'pet:view'
  UNION ALL SELECT 'pet:relation:manage'    UNION ALL SELECT 'pet:health:view'
  UNION ALL SELECT 'pet:health:manage'      UNION ALL SELECT 'appointment:create'
  UNION ALL SELECT 'appointment:cancel:self' UNION ALL SELECT 'appointment:modify'
  UNION ALL SELECT 'appointment:view:self'  UNION ALL SELECT 'order:pay'
  UNION ALL SELECT 'order:view:self'        UNION ALL SELECT 'order:refund:apply'
  UNION ALL SELECT 'record:view:pet'        UNION ALL SELECT 'medical:record:view'
  UNION ALL SELECT 'boarding:care:view'     UNION ALL SELECT 'rec:browse'
  UNION ALL SELECT 'rec:apply'              UNION ALL SELECT 'rec:application:view:self'
) c WHERE r.code = 'CUSTOMER';

INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT (@i := @i + 1), r.id, c.code FROM emp_role r JOIN (
  SELECT 'account:profile:manage' AS code
  UNION ALL SELECT 'emp:self:view'          UNION ALL SELECT 'schedule:self:view'
  UNION ALL SELECT 'schedule:leave:apply'   UNION ALL SELECT 'store:browse'
  UNION ALL SELECT 'catalog:browse'         UNION ALL SELECT 'availability:view'
  UNION ALL SELECT 'ntf:receive'            UNION ALL SELECT 'account:disable'
  UNION ALL SELECT 'pet:view'               UNION ALL SELECT 'pet:health:view'
  UNION ALL SELECT 'store:manage'           UNION ALL SELECT 'store:info:update'
  UNION ALL SELECT 'store:stats:view'       UNION ALL SELECT 'catalog:service:manage'
  UNION ALL SELECT 'catalog:store_service:manage' UNION ALL SELECT 'catalog:skill:manage'
  UNION ALL SELECT 'emp:create'             UNION ALL SELECT 'emp:update'
  UNION ALL SELECT 'emp:role:assign'        UNION ALL SELECT 'emp:skill:assign'
  UNION ALL SELECT 'emp:view'               UNION ALL SELECT 'schedule:manage'
  UNION ALL SELECT 'schedule:leave:approve' UNION ALL SELECT 'resource:manage'
  UNION ALL SELECT 'appointment:cancel:store' UNION ALL SELECT 'appointment:modify'
  UNION ALL SELECT 'appointment:view:store' UNION ALL SELECT 'appointment:assign'
  UNION ALL SELECT 'appointment:reassign'   UNION ALL SELECT 'appointment:advance'
  UNION ALL SELECT 'order:view:store'       UNION ALL SELECT 'order:refund:approve'
  UNION ALL SELECT 'order:close'            UNION ALL SELECT 'task:accept'
  UNION ALL SELECT 'record:view:self'       UNION ALL SELECT 'record:view:store'
  UNION ALL SELECT 'record:view:pet'        UNION ALL SELECT 'medical:record:view'
  UNION ALL SELECT 'boarding:care:view'     UNION ALL SELECT 'boarding:capacity:view'
  UNION ALL SELECT 'rec:publish'            UNION ALL SELECT 'rec:application:manage'
  UNION ALL SELECT 'ntf:send:system'
) c WHERE r.code = 'ADMIN';

INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT (@i := @i + 1), r.id, c.code FROM emp_role r JOIN (
  SELECT 'account:profile:manage' AS code
  UNION ALL SELECT 'emp:self:view'          UNION ALL SELECT 'schedule:self:view'
  UNION ALL SELECT 'schedule:leave:apply'   UNION ALL SELECT 'store:browse'
  UNION ALL SELECT 'catalog:browse'         UNION ALL SELECT 'availability:view'
  UNION ALL SELECT 'ntf:receive'            UNION ALL SELECT 'pet:view'
  UNION ALL SELECT 'pet:health:view'        UNION ALL SELECT 'store:info:update'
  UNION ALL SELECT 'store:stats:view'       UNION ALL SELECT 'catalog:store_service:manage'
  UNION ALL SELECT 'emp:create'             UNION ALL SELECT 'emp:update'
  UNION ALL SELECT 'emp:role:assign'        UNION ALL SELECT 'emp:skill:assign'
  UNION ALL SELECT 'emp:view'               UNION ALL SELECT 'schedule:manage'
  UNION ALL SELECT 'schedule:leave:approve' UNION ALL SELECT 'resource:manage'
  UNION ALL SELECT 'appointment:cancel:store' UNION ALL SELECT 'appointment:modify'
  UNION ALL SELECT 'appointment:view:store' UNION ALL SELECT 'appointment:view:task'
  UNION ALL SELECT 'appointment:assign'     UNION ALL SELECT 'appointment:reassign'
  UNION ALL SELECT 'appointment:checkin'    UNION ALL SELECT 'appointment:advance'
  UNION ALL SELECT 'order:pay'              UNION ALL SELECT 'order:view:store'
  UNION ALL SELECT 'order:refund:approve'   UNION ALL SELECT 'task:accept'
  UNION ALL SELECT 'task:start'             UNION ALL SELECT 'task:finish'
  UNION ALL SELECT 'record:bath:create'     UNION ALL SELECT 'record:walk:create'
  UNION ALL SELECT 'record:view:self'       UNION ALL SELECT 'record:view:store'
  UNION ALL SELECT 'record:view:pet'        UNION ALL SELECT 'medical:record:view'
  UNION ALL SELECT 'boarding:checkin'       UNION ALL SELECT 'boarding:checkout'
  UNION ALL SELECT 'boarding:care:create'   UNION ALL SELECT 'boarding:care:view'
  UNION ALL SELECT 'boarding:capacity:view' UNION ALL SELECT 'rec:publish'
  UNION ALL SELECT 'rec:application:manage'
) c WHERE r.code = 'STORE_MANAGER';

INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT (@i := @i + 1), r.id, c.code FROM emp_role r JOIN (
  SELECT 'account:profile:manage' AS code
  UNION ALL SELECT 'emp:self:view'          UNION ALL SELECT 'schedule:self:view'
  UNION ALL SELECT 'schedule:leave:apply'   UNION ALL SELECT 'store:browse'
  UNION ALL SELECT 'catalog:browse'         UNION ALL SELECT 'availability:view'
  UNION ALL SELECT 'ntf:receive'            UNION ALL SELECT 'pet:view'
  UNION ALL SELECT 'pet:health:view'        UNION ALL SELECT 'pet:health:manage'
  UNION ALL SELECT 'appointment:view:task'  UNION ALL SELECT 'appointment:advance'
  UNION ALL SELECT 'task:accept'            UNION ALL SELECT 'task:start'
  UNION ALL SELECT 'task:finish'            UNION ALL SELECT 'record:view:self'
  UNION ALL SELECT 'record:view:store'      UNION ALL SELECT 'record:view:pet'
  UNION ALL SELECT 'medical:record:create'  UNION ALL SELECT 'medical:record:update'
  UNION ALL SELECT 'medical:record:view'    UNION ALL SELECT 'medical:vaccine:manage'
  UNION ALL SELECT 'medical:report:upload'  UNION ALL SELECT 'medical:prescription:create'
  UNION ALL SELECT 'boarding:care:view'
) c WHERE r.code = 'DOCTOR';

INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT (@i := @i + 1), r.id, c.code FROM emp_role r JOIN (
  SELECT 'account:profile:manage' AS code
  UNION ALL SELECT 'emp:self:view'          UNION ALL SELECT 'schedule:self:view'
  UNION ALL SELECT 'schedule:leave:apply'   UNION ALL SELECT 'store:browse'
  UNION ALL SELECT 'catalog:browse'         UNION ALL SELECT 'availability:view'
  UNION ALL SELECT 'ntf:receive'            UNION ALL SELECT 'pet:view'
  UNION ALL SELECT 'pet:health:view'        UNION ALL SELECT 'appointment:view:task'
  UNION ALL SELECT 'appointment:advance'    UNION ALL SELECT 'task:accept'
  UNION ALL SELECT 'task:start'             UNION ALL SELECT 'task:finish'
  UNION ALL SELECT 'record:bath:create'     UNION ALL SELECT 'record:view:self'
  UNION ALL SELECT 'record:view:pet'
) c WHERE r.code = 'GROOMER';

INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT (@i := @i + 1), r.id, c.code FROM emp_role r JOIN (
  SELECT 'account:profile:manage' AS code
  UNION ALL SELECT 'emp:self:view'          UNION ALL SELECT 'schedule:self:view'
  UNION ALL SELECT 'schedule:leave:apply'   UNION ALL SELECT 'store:browse'
  UNION ALL SELECT 'catalog:browse'         UNION ALL SELECT 'availability:view'
  UNION ALL SELECT 'ntf:receive'            UNION ALL SELECT 'pet:view'
  UNION ALL SELECT 'appointment:view:task'  UNION ALL SELECT 'appointment:advance'
  UNION ALL SELECT 'task:accept'            UNION ALL SELECT 'task:start'
  UNION ALL SELECT 'task:finish'            UNION ALL SELECT 'record:walk:create'
  UNION ALL SELECT 'record:view:self'       UNION ALL SELECT 'record:view:pet'
) c WHERE r.code = 'DOG_WALKER';

INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT (@i := @i + 1), r.id, c.code FROM emp_role r JOIN (
  SELECT 'account:profile:manage' AS code
  UNION ALL SELECT 'emp:self:view'          UNION ALL SELECT 'schedule:self:view'
  UNION ALL SELECT 'schedule:leave:apply'   UNION ALL SELECT 'store:browse'
  UNION ALL SELECT 'catalog:browse'         UNION ALL SELECT 'availability:view'
  UNION ALL SELECT 'ntf:receive'            UNION ALL SELECT 'pet:view'
  UNION ALL SELECT 'pet:health:view'        UNION ALL SELECT 'pet:health:manage'
  UNION ALL SELECT 'appointment:view:task'  UNION ALL SELECT 'appointment:advance'
  UNION ALL SELECT 'task:accept'            UNION ALL SELECT 'task:start'
  UNION ALL SELECT 'task:finish'            UNION ALL SELECT 'record:view:self'
  UNION ALL SELECT 'record:view:pet'        UNION ALL SELECT 'medical:record:view'
  UNION ALL SELECT 'medical:vaccine:manage' UNION ALL SELECT 'boarding:checkin'
  UNION ALL SELECT 'boarding:care:create'   UNION ALL SELECT 'boarding:care:view'
  UNION ALL SELECT 'boarding:capacity:view'
) c WHERE r.code = 'CARETAKER';

INSERT INTO emp_role_permission (id, role_id, permission_code)
SELECT (@i := @i + 1), r.id, c.code FROM emp_role r JOIN (
  SELECT 'account:profile:manage' AS code
  UNION ALL SELECT 'emp:self:view'          UNION ALL SELECT 'schedule:self:view'
  UNION ALL SELECT 'schedule:leave:apply'   UNION ALL SELECT 'store:browse'
  UNION ALL SELECT 'catalog:browse'         UNION ALL SELECT 'availability:view'
  UNION ALL SELECT 'ntf:receive'            UNION ALL SELECT 'pet:view'
  UNION ALL SELECT 'pet:health:view'        UNION ALL SELECT 'emp:view'
  UNION ALL SELECT 'appointment:create'     UNION ALL SELECT 'appointment:cancel:store'
  UNION ALL SELECT 'appointment:modify'     UNION ALL SELECT 'appointment:view:store'
  UNION ALL SELECT 'appointment:assign'     UNION ALL SELECT 'appointment:reassign'
  UNION ALL SELECT 'appointment:checkin'    UNION ALL SELECT 'appointment:advance'
  UNION ALL SELECT 'order:pay'              UNION ALL SELECT 'order:view:store'
  UNION ALL SELECT 'task:accept'            UNION ALL SELECT 'record:view:store'
  UNION ALL SELECT 'record:view:pet'        UNION ALL SELECT 'medical:record:view'
  UNION ALL SELECT 'boarding:checkin'       UNION ALL SELECT 'boarding:checkout'
  UNION ALL SELECT 'boarding:care:view'     UNION ALL SELECT 'boarding:capacity:view'
  UNION ALL SELECT 'rec:browse'
) c WHERE r.code = 'RECEPTIONIST';

-- ========== 4. 技能字典（ADMIN 可维护，种子起步集） ==========
INSERT INTO emp_skill (id, code, name, category) VALUES
(301, 'BATH',          '洗澡',       'GROOMING'),
(302, 'HAIRCUT',       '剪毛造型',   'GROOMING'),
(303, 'NAIL_CARE',     '指甲护理',   'GROOMING'),
(304, 'EAR_CLEAN',     '耳道清洁',   'GROOMING'),
(305, 'WALK',          '遛狗',       'WALK'),
(306, 'BOARDING_CARE', '托管护理',   'BOARDING'),
(307, 'MEDICAL_EXAM',  '诊疗',       'MEDICAL'),
(308, 'VACCINATION',   '疫苗接种',   'MEDICAL');

-- ========== 5. 平台服务目录（6 项） ==========
INSERT INTO cat_service (id, code, name, description, category, required_duration_min, fulfillment_type, allow_user_pick_employee, status) VALUES
(401, 'PET_BATH',     '宠物洗澡', '含清洁、吹干、基础护理', 'GROOMING', 60,   'INSTANT',   1, 'ON'),
(402, 'PET_GROOMING', '宠物美容', '剪毛造型、指甲、耳道清洁', 'GROOMING', 90,   'INSTANT',   1, 'ON'),
(403, 'DOG_WALK',     '户外遛狗', '按次遛放，含排便记录',   'WALK',     60,   'INSTANT',   1, 'ON'),
(404, 'PET_BOARDING', '宠物托管', '按天托管，含每日护理记录', 'BOARDING', 1440, 'MULTI_DAY', 0, 'ON'),
(405, 'PET_MEDICAL',  '宠物诊疗', '医生接诊、诊断、治疗',   'MEDICAL',  30,   'INSTANT',   1, 'ON'),
(406, 'VACCINATION',  '疫苗接种', '常规疫苗接种与登记',     'MEDICAL',  30,   'INSTANT',   1, 'ON');

-- ========== 6. 服务所需技能声明 ==========
INSERT INTO cat_service_skill (id, service_id, skill_id, is_required) VALUES
(411, 401, 301, 1),
(412, 402, 302, 1),
(413, 402, 303, 0),
(414, 403, 305, 1),
(415, 404, 306, 1),
(416, 405, 307, 1),
(417, 406, 308, 1);

-- ========== 7. 平台配置默认值（ADR-005-9/12、ADR-006-6） ==========
INSERT INTO sys_config (id, config_key, config_value, remark) VALUES
(501, 'appointment.max_advance_days',              '30',    '最早可提前预约天数'),
(502, 'appointment.min_lead_hours',                '2',     '最晚须在服务开始前N小时'),
(503, 'appointment.pay_timeout_minutes',           '15',    '支付超时分钟数'),
(504, 'appointment.employee_buffer_minutes',       '0',     '员工预约间隔buffer'),
(505, 'order.unpaid_limit_per_user',               '3',     '单用户未支付订单上限'),
(506, 'order.daily_create_limit_per_user_store',   '10',    '单用户单店单日创建预约上限'),
(507, 'refund.auto_approve_limit_fen',             '50000', '店长可批退款上限(分)=500元'),
(508, 'size_category.weight_thresholds',           '{"smallMaxKg":10,"mediumMaxKg":25}', '体重→笼位类型映射'),
(509, 'medical.record_edit_window_hours',          '72',    '病历修改时间窗'),
(510, 'notification.service_remind_minutes',       '120',   '服务开始前提醒分钟数');
