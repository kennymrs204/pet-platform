package com.petplatform.fulfillment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.fulfillment.api.ServiceRecordCommandApi;
import com.petplatform.fulfillment.api.dto.BathRecordDraft;
import com.petplatform.fulfillment.api.dto.WalkRecordDraft;
import com.petplatform.fulfillment.api.dto.WalkTrackDraft;
import com.petplatform.fulfillment.api.dto.WalkTrackPoint;
import com.petplatform.fulfillment.api.dto.WalkTrackView;
import com.petplatform.fulfillment.domain.BathRecordEntity;
import com.petplatform.fulfillment.domain.WalkRecordEntity;
import com.petplatform.fulfillment.domain.WalkTrackEntity;
import com.petplatform.fulfillment.mapper.BathRecordMapper;
import com.petplatform.fulfillment.mapper.WalkRecordMapper;
import com.petplatform.fulfillment.mapper.WalkTrackMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 履约记录写入（ADR-048）。<b>本模块第一个写侧</b>：之前 {@code ful_*_record} 只有读，
 * INSERT 只存在于验收脚本里。
 *
 * <p>刻意<b>不加</b>事务注解：调用方（app 装配层的"完成服务"编排）已经开了事务，
 * 记录必须与预约状态同生同死；在这里另起一层只会有两种结果——REQUIRED 合并进外层（等于没写），
 * 或 REQUIRES_NEW 把"状态已改、记录失败"变成合法的中间态（正是我们要禁止的）。
 *
 * <p>校验只覆盖表的 NOT NULL 列，不做业务判断（该不该完成由 appointment 侧的状态机裁决）。
 * 缺列一律 10001 明确报出来，而不是补个默认值——补默认值等于把调用方的编排 bug 写成一条脏记录。
 */
@Slf4j
@Service
public class ServiceRecordCommandService implements ServiceRecordCommandApi {

    private static final int ROLE_MAX = 32;

    private final BathRecordMapper bathMapper;
    private final WalkRecordMapper walkMapper;
    private final WalkTrackMapper trackMapper;
    private final WalkTrackCodec trackCodec;
    private final ObjectMapper json;

    public ServiceRecordCommandService(BathRecordMapper bathMapper, WalkRecordMapper walkMapper,
                                       WalkTrackMapper trackMapper, WalkTrackCodec trackCodec,
                                       ObjectMapper json) {
        this.bathMapper = bathMapper;
        this.walkMapper = walkMapper;
        this.trackMapper = trackMapper;
        this.trackCodec = trackCodec;
        this.json = json;
    }

    @Override
    public Long createBath(BathRecordDraft draft) {
        if (draft == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "履约记录不能为空");
        }
        requireCommon(draft.getAppointmentId(), draft.getPetId(), draft.getEmployeeId(),
                draft.getExecutorRole(), draft.getStartedAt(), draft.getFinishedAt());
        if (draft.getStoreServiceId() == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "洗护记录缺 storeServiceId");
        }
        BathRecordEntity e = new BathRecordEntity();
        e.setAppointmentId(draft.getAppointmentId());
        e.setPetId(draft.getPetId());
        e.setEmployeeId(draft.getEmployeeId());
        e.setStoreServiceId(draft.getStoreServiceId());
        e.setExecutorRole(draft.getExecutorRole());
        e.setByManager(draft.isByManager());
        e.setStartedAt(draft.getStartedAt());
        e.setFinishedAt(draft.getFinishedAt());
        e.setServiceItems(writeJson(draft.getServiceItems()));
        e.setPhotos(writeJson(draft.getPhotos()));
        e.setRemark(trim(draft.getRemark()));
        bathMapper.insert(e);
        log.info("洗护履约记录已写 id={} appointmentId={} employeeId={} items={}",
                e.getId(), e.getAppointmentId(), e.getEmployeeId(),
                draft.getServiceItems() == null ? 0 : draft.getServiceItems().size());
        return e.getId();
    }

    @Override
    public Long createWalk(WalkRecordDraft draft) {
        if (draft == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "履约记录不能为空");
        }
        requireCommon(draft.getAppointmentId(), draft.getPetId(), draft.getEmployeeId(),
                draft.getExecutorRole(), draft.getStartedAt(), draft.getFinishedAt());
        if (draft.getDurationMin() == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "遛狗记录缺 durationMin");
        }
        WalkRecordEntity e = new WalkRecordEntity();
        e.setAppointmentId(draft.getAppointmentId());
        e.setPetId(draft.getPetId());
        e.setEmployeeId(draft.getEmployeeId());
        e.setExecutorRole(draft.getExecutorRole());
        e.setByManager(draft.isByManager());
        e.setStartedAt(draft.getStartedAt());
        e.setFinishedAt(draft.getFinishedAt());
        e.setDurationMin(draft.getDurationMin());
        e.setRemark(trim(draft.getRemark()));
        walkMapper.insert(e);
        log.info("遛狗履约记录已写 id={} appointmentId={} employeeId={} durationMin={}",
                e.getId(), e.getAppointmentId(), e.getEmployeeId(), e.getDurationMin());
        return e.getId();
    }

    /**
     * 追加照片（append-only）。串行点是 {@code SELECT … FOR UPDATE}：先锁住该记录行，
     * 合并后整列写回，两个并发追加者不会互相覆盖；重复 key 静默去重（同一张图重试上传）。
     * 数量闸在合并后判定——只看入参会放过"已有 8 张再传 5 张"这种越界。
     */
    @Override
    public List<String> appendWalkPhotos(Long appointmentId, List<String> mediaKeys) {
        if (appointmentId == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "appointmentId 必填");
        }
        if (mediaKeys == null || mediaKeys.isEmpty()) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "至少提交一张照片");
        }
        WalkRecordEntity record = walkMapper.selectByAppointmentForUpdate(appointmentId);
        if (record == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        List<String> merged = new ArrayList<>(readPhotos(record.getPhotos()));
        for (String raw : mediaKeys) {
            String key = raw == null ? "" : raw.trim();
            if (key.isEmpty()) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "照片 key 不能为空");
            }
            if (!merged.contains(key)) {
                merged.add(key);
            }
        }
        if (merged.size() > MAX_WALK_PHOTOS) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID,
                    "遛狗照片最多 " + MAX_WALK_PHOTOS + " 张");
        }
        if (walkMapper.updatePhotos(record.getId(), writeJson(merged)) == 0) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        log.info("遛狗记录追加照片 appointmentId={} 本次={} 总数={}",
                appointmentId, mediaKeys.size(), merged.size());
        return merged;
    }

    /** JSON 列脏值只降级不抛：一条坏数据不该让"补照片"整单失败（与读侧同口径）。 */
    private List<String> readPhotos(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = json.readValue(raw, new TypeReference<List<String>>() {
            });
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("遛狗记录 photos 解析失败，按空列表处理: {}", e.getMessage());
            return List.of();
        }
    }

    private void requireCommon(Long appointmentId, Long petId, Long employeeId, String executorRole,
                               LocalDateTime startedAt, LocalDateTime finishedAt) {
        if (appointmentId == null || petId == null || employeeId == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "履约记录缺预约/宠物/执行人");
        }
        if (executorRole == null || executorRole.isBlank()) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "履约记录缺执行人角色");
        }
        if (executorRole.length() > ROLE_MAX) {
            // 静默截断会让"执行人当时角色"变成猜的，宁可报出来让调用方给准
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "执行人角色超出 32 字符");
        }
        if (startedAt == null || finishedAt == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID,
                    "履约记录缺起止时间（该单未开始服务？）");
        }
    }

    /* ---- GPS 轨迹（V16，2026-10-24） ---- */

    /**
     * 保存一次遛狗轨迹：清洗 → 派生 distance/duration/pointCount → INSERT。
     *
     * <p>幂等设计：{@code uk_appt(appointment_id)} 保证一服务一条；撞唯一键时
     * 若 trackId 与已存行相同 ⇒ 返回已存轨迹（客户端重试）；不同 ⇒ 10005（禁止覆盖）。
     * 服务端不接收客户端计算出的里程，任何"我算好了"的字段都不在入参里。
     */
    @Override
    public WalkTrackView saveWalkTrack(WalkTrackDraft draft) {
        if (draft == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "轨迹不能为空");
        }
        if (draft.getAppointmentId() == null || draft.getPetId() == null || draft.getStoreId() == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "轨迹缺预约/宠物/门店");
        }
        String trackId = draft.getTrackId() == null ? "" : draft.getTrackId().trim();
        if (!trackId.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "trackId 须为 8~64 位字母数字/下划线/短横线");
        }
        List<WalkTrackPoint> raw = draft.getPoints();
        if (raw == null || raw.isEmpty()) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "轨迹点不能为空");
        }
        if (raw.size() > MAX_TRACK_POINTS) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "轨迹点超过上限 " + MAX_TRACK_POINTS);
        }
        int paused = Math.max(0, draft.getPausedSec());
        if (paused > MAX_TRACK_PAUSE_SEC) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "累计暂停时长超过上限");
        }
        TrackGeometry.Cleaned cleaned = TrackGeometry.sanitize(raw, paused);
        if (cleaned == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "轨迹有效点不足（至少 2 个），无法形成有效轨迹");
        }
        if (cleaned.durationSec() > TrackGeometry.MAX_DURATION_SEC) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "实际记录时长超过 12 小时上限");
        }

        WalkTrackEntity e = new WalkTrackEntity();
        e.setAppointmentId(draft.getAppointmentId());
        e.setPetId(draft.getPetId());
        e.setStoreId(draft.getStoreId());
        e.setEmployeeId(draft.getEmployeeId());
        e.setTrackId(trackId);
        e.setStartedAt(toLocal(cleaned.points().get(0).getAtMillis()));
        e.setEndedAt(toLocal(cleaned.points().get(cleaned.points().size() - 1).getAtMillis()));
        e.setDurationSec(cleaned.durationSec());
        e.setPausedSec(paused);
        e.setDistanceM(cleaned.distanceM());
        e.setPointCount(cleaned.points().size());
        e.setPoints(trackCodec.write(cleaned.points()));
        try {
            trackMapper.insert(e);
        } catch (DuplicateKeyException dup) {
            return replayOrConflict(draft.getAppointmentId(), trackId);
        }
        log.info("遛狗轨迹已保存 appointmentId={} trackId={} points={} distanceM={} durationSec={}",
                e.getAppointmentId(), trackId, e.getPointCount(), e.getDistanceM(), e.getDurationSec());
        return viewOf(e, TrackGeometry.polyline(cleaned.points(), TrackGeometry.POLYLINE_MAX_POINTS));
    }

    private WalkTrackView replayOrConflict(Long appointmentId, String trackId) {
        WalkTrackEntity existing = trackMapper.selectByAppointment(appointmentId);
        if (existing != null && trackId.equals(existing.getTrackId())) {
            log.info("轨迹重复提交（trackId 相同），按幂等返回 appointmentId={}", appointmentId);
            return viewOf(existing, null);
        }
        throw new BusinessException(CommonErrorCode.DUPLICATE_REQUEST, "该服务已保存过轨迹，不能重复提交");
    }

    private WalkTrackView viewOf(WalkTrackEntity e, List<double[]> polyline) {
        List<double[]> line = polyline != null
                ? polyline
                : TrackGeometry.polyline(trackCodec.read(e.getPoints()), TrackGeometry.POLYLINE_MAX_POINTS);
        return WalkTrackView.builder()
                .id(e.getId()).appointmentId(e.getAppointmentId()).trackId(e.getTrackId())
                .startedAt(e.getStartedAt()).endedAt(e.getEndedAt())
                .durationSec(e.getDurationSec() == null ? 0 : e.getDurationSec())
                .pausedSec(e.getPausedSec() == null ? 0 : e.getPausedSec())
                .distanceM(e.getDistanceM() == null ? 0 : e.getDistanceM())
                .pointCount(e.getPointCount() == null ? 0 : e.getPointCount())
                .polyline(line)
                .build();
    }

    private static LocalDateTime toLocal(long millis) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault());
    }

    /** 只在拿到非空列表时序列化；空列表写空数组而不是 null，两者在读侧是可区分的（"没勾"与"没传字段"）。 */
    private String writeJson(List<String> values) {
        if (values == null) {
            return null;
        }
        try {
            return json.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "履约记录附件格式非法");
        }
    }

    private String trim(String remark) {
        if (remark == null) {
            return null;
        }
        String v = remark.trim();
        return v.isEmpty() ? null : v.length() > 500 ? v.substring(0, 500) : v;
    }
}
