package com.petplatform.fulfillment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.fulfillment.api.ServiceRecordQueryApi;
import com.petplatform.fulfillment.api.dto.LastServiceView;
import com.petplatform.fulfillment.api.dto.ServiceRecordBrief;
import com.petplatform.fulfillment.api.dto.WalkRecordBrief;
import com.petplatform.fulfillment.api.dto.WalkTrackView;
import com.petplatform.fulfillment.domain.WalkTrackEntity;
import com.petplatform.fulfillment.mapper.ServiceRecordQueryMapper;
import com.petplatform.fulfillment.mapper.WalkTrackMapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ServiceRecordQueryService implements ServiceRecordQueryApi {

    private final ServiceRecordQueryMapper mapper;
    private final WalkTrackMapper trackMapper;
    private final WalkTrackCodec trackCodec;
    private final ObjectMapper json;

    public ServiceRecordQueryService(ServiceRecordQueryMapper mapper, WalkTrackMapper trackMapper,
                                     WalkTrackCodec trackCodec, ObjectMapper json) {
        this.mapper = mapper;
        this.trackMapper = trackMapper;
        this.trackCodec = trackCodec;
        this.json = json;
    }

    @Override
    public List<LastServiceView> lastServices(Collection<Long> petIds) {
        if (petIds == null || petIds.isEmpty()) {
            return List.of();
        }
        return mapper.selectLastServices(petIds).stream()
                .map(this::toLastServiceView)
                .toList();
    }

    @Override
    public List<LastServiceView> lastServicesInAppointments(Collection<Long> petIds,
                                                            Collection<Long> appointmentIds) {
        if (petIds == null || petIds.isEmpty() || appointmentIds == null || appointmentIds.isEmpty()) {
            return List.of();
        }
        return mapper.selectLastServicesInAppointments(petIds, appointmentIds).stream()
                .map(this::toLastServiceView)
                .toList();
    }

    private LastServiceView toLastServiceView(ServiceRecordQueryMapper.LastServiceRow r) {
        return LastServiceView.builder()
                .petId(r.getPetId())
                .appointmentId(r.getAppointmentId())
                .type(r.getType())
                .finishedAt(r.getFinishedAt())
                .build();
    }

    /**
     * 按预约批量取洗澡/美容记录（ADR-038）。
     * 无记录的预约不出现在返回值里——看板要能区分"这单还没做"和"查不到数据"，
     * 把缺数据伪装成空记录会让"未开始"和"记录被删"长得一模一样。
     */
    @Override
    public Map<Long, ServiceRecordBrief> bathRecordsByAppointments(Collection<Long> appointmentIds) {
        if (appointmentIds == null || appointmentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ServiceRecordBrief> out = new LinkedHashMap<>();
        for (ServiceRecordQueryMapper.BathRecordRow r : mapper.selectBathRecordsByAppointments(appointmentIds)) {
            out.put(r.getAppointmentId(), ServiceRecordBrief.builder()
                    .appointmentId(r.getAppointmentId())
                    .employeeId(r.getEmployeeId())
                    .startedAt(r.getStartedAt())
                    .finishedAt(r.getFinishedAt())
                    .serviceItems(readStringList(r.getServiceItems()))
                    .remark(r.getRemark())
                    .photoCount(r.getPhotoCount() == null ? 0 : r.getPhotoCount())
                    .byManager(r.getByManager() != null && r.getByManager() == 1)
                    .build());
        }
        return out;
    }

    /**
     * 按预约批量取遛狗记录（ADR-049）。无记录的预约不出现在返回值里，与洗澡版同一口径：
     * 看板要能区分「这单还没遛」和「记录被删了」，把缺数据伪装成空记录会让两者分不开。
     */
    @Override
    public Map<Long, WalkRecordBrief> walkRecordsByAppointments(Collection<Long> appointmentIds) {
        if (appointmentIds == null || appointmentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, WalkRecordBrief> out = new LinkedHashMap<>();
        for (ServiceRecordQueryMapper.WalkRecordRow r : mapper.selectWalkRecordsByAppointments(appointmentIds)) {
            out.put(r.getAppointmentId(), WalkRecordBrief.builder()
                    .appointmentId(r.getAppointmentId())
                    .employeeId(r.getEmployeeId())
                    .startedAt(r.getStartedAt())
                    .finishedAt(r.getFinishedAt())
                    .durationMin(r.getDurationMin())
                    .remark(r.getRemark())
                    .byManager(r.getByManager() != null && r.getByManager() == 1)
                    .photos(readStringList(r.getPhotos()))
                    .build());
        }
        return out;
    }

    /**
     * 按预约批量取遛狗 GPS 轨迹摘要（V16）。与履约记录同口径：无轨迹的预约不出现在 Map 里。
     * 距离/时长/点数取持久化值；polyline 是读时抽稀的展示数据（不反哺任何计算）。
     */
    @Override
    public Map<Long, WalkTrackView> walkTracksByAppointments(Collection<Long> appointmentIds) {
        if (appointmentIds == null || appointmentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, WalkTrackView> out = new LinkedHashMap<>();
        for (WalkTrackEntity e : trackMapper.selectByAppointments(appointmentIds)) {
            out.put(e.getAppointmentId(), WalkTrackView.builder()
                    .id(e.getId()).appointmentId(e.getAppointmentId()).trackId(e.getTrackId())
                    .startedAt(e.getStartedAt()).endedAt(e.getEndedAt())
                    .durationSec(e.getDurationSec() == null ? 0 : e.getDurationSec())
                    .pausedSec(e.getPausedSec() == null ? 0 : e.getPausedSec())
                    .distanceM(e.getDistanceM() == null ? 0 : e.getDistanceM())
                    .pointCount(e.getPointCount() == null ? 0 : e.getPointCount())
                    .polyline(TrackGeometry.polyline(trackCodec.read(e.getPoints()),
                            TrackGeometry.POLYLINE_MAX_POINTS))
                    .build());
        }
        return out;
    }

    /** JSON 列脏值只降级不抛：一条坏记录不能拖垮整块看板（同 ADR-015 护理记录读侧口径）。 */
    private List<String> readStringList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = json.readValue(raw, new TypeReference<List<String>>() {
            });
            // JSON 字面量 null 也归一成空列表：出参契约是"无照片=空数组"，不给调用方留两套空值
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }
}
