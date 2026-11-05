-- ============================================================================
-- V16：遛狗 GPS 轨迹（一次服务一条，保存后不可覆盖）
--
-- 为什么单独建表而不是塞进 ful_walk_record.track_summary：
--   ① 履约记录只在"完成服务"那一刻才 INSERT，而轨迹在服务中（SERVING）就要落库，
--      否则页面刷新即丢、且无法在完成前回显（需求六：只在服务时段采集、结束后保存）；
--   ② 完成流程与轨迹保存解耦：轨迹写失败绝不能回滚/阻断预约状态推进（需求二.4）；
--   ③ ful_walk_record.distance_m/track_summary 仍保持 ADR-048 的"无采集不填"口径，
--      轨迹的距离/时长/点数以本表为唯一事实来源，口径在交付报告中写明。
--
-- 语义：
--   points 是服务端清洗后的有效点 [[lat,lng,atMillis],...]（去重/去跳点/按时间序），
--   客户端原始点不落库；distance_m/duration_sec/point_count 均由服务端从有效点派生后持久化，
--   读侧不再重算（避免两套算法漂移）。
--   duration_sec = 末点时间 − 首点时间 − paused_sec（暂停期间不采集也不计程）。
--   uk_appt：一条预约至多一条轨迹 ⇒ "完成后再次提交"由唯一键兜住（并发安全）；
--   uk_track：同一 trackId 重试触发的重复插入可识别为幂等重放。
--
-- 回滚（如必要）：DROP TABLE ful_walk_track;
-- ============================================================================

CREATE TABLE ful_walk_track (
  id             BIGINT       NOT NULL,
  appointment_id BIGINT       NOT NULL,
  pet_id         BIGINT       NOT NULL,
  store_id       BIGINT       NOT NULL,
  employee_id    BIGINT       NULL COMMENT '保存时的操作人（员工）；平台代操作时可能为空',
  track_id       VARCHAR(64)  NOT NULL COMMENT '客户端幂等键（同一轨迹重试时必须相同）',
  started_at     DATETIME(3)  NOT NULL COMMENT '首个有效点的采集时间',
  ended_at       DATETIME(3)  NOT NULL COMMENT '最后一个有效点的采集时间',
  duration_sec   INT          NOT NULL COMMENT '实际记录时长=末点−首点−暂停',
  paused_sec     INT          NOT NULL DEFAULT 0,
  distance_m     INT          NOT NULL COMMENT '服务端按 HAVERSINE 累加、已剔除跳点',
  point_count    INT          NOT NULL COMMENT '有效点数（清洗后）',
  points         JSON         NOT NULL COMMENT '有效轨迹点 [[lat,lng,atMillis],...]',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted        BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_appt (appointment_id),
  UNIQUE KEY uk_track (appointment_id, track_id),
  KEY idx_pet (pet_id)
) COMMENT='遛狗 GPS 轨迹（一次服务一条，保存后不可覆盖）';

-- 自检：表存在且关键列齐全（期望 k=V16 walk track, n=16）
SELECT 'V16 walk track' AS k,
       (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ful_walk_track') AS n;
