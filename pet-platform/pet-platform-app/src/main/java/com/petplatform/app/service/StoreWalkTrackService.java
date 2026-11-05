package com.petplatform.app.service;

import com.petplatform.appointment.api.AppointmentTaskApi;
import com.petplatform.appointment.api.AppointmentErrorCode;
import com.petplatform.appointment.api.dto.AppointmentTaskView;
import com.petplatform.catalog.api.CatalogQueryApi;
import com.petplatform.catalog.api.dto.StoreServiceView;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.fulfillment.api.ServiceRecordCommandApi;
import com.petplatform.fulfillment.api.dto.WalkTrackDraft;
import com.petplatform.fulfillment.api.dto.WalkTrackPoint;
import com.petplatform.fulfillment.api.dto.WalkTrackView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 遛狗轨迹保存的跨模块编排（2026-10-24）。
 *
 * <p>授权与"开始/完成服务"同源：{@link AppointmentTaskApi#taskOf} 负责门店隔离（跨店 10004）
 * 与人员范围（执行人只能操作派给自己的，否则 10003）；服务类型只认目录 {@code DOG_WALK}；
 * 状态只允许 {@code SERVING}/{@code COMPLETED}——轨迹是"实际发生过的那次遛狗"的附属事实，
 * 未开始或已取消的服务不能凭空产生轨迹。
 *
 * <p>距离/时长/点数的派生在 fulfillment 侧（{@code TrackGeometry}），本类不参与计算，
 * 也不接收任何客户端计算出的里程。保存失败不会改动预约状态（本操作只写轨迹表）。
 */
@Service
public class StoreWalkTrackService {

    private static final String CODE_WALK = "DOG_WALK";

    private final AppointmentTaskApi appointmentTaskApi;
    private final CatalogQueryApi catalogApi;
    private final ServiceRecordCommandApi recordApi;

    public StoreWalkTrackService(AppointmentTaskApi appointmentTaskApi, CatalogQueryApi catalogApi,
                                 ServiceRecordCommandApi recordApi) {
        this.appointmentTaskApi = appointmentTaskApi;
        this.catalogApi = catalogApi;
        this.recordApi = recordApi;
    }

    @Transactional
    public WalkTrackView save(Long storeId, AuthPrincipal principal, Long appointmentId,
                              String trackId, int pausedSec, List<WalkTrackPoint> points) {
        boolean storeWide = principal.permissions().contains("appointment:view:store");
        AppointmentTaskView task = appointmentTaskApi.taskOf(appointmentId, storeId,
                principal.employeeId(), storeWide);
        if (!"SERVING".equals(task.getStatus()) && !"COMPLETED".equals(task.getStatus())) {
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION,
                    "仅在服务中或已完成时可保存遛狗轨迹");
        }
        StoreServiceView svc = catalogApi.storeServiceOf(task.getStoreServiceId());
        if (svc == null || !CODE_WALK.equals(svc.getServiceCode())) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "非遛狗服务，不能保存轨迹");
        }
        return recordApi.saveWalkTrack(WalkTrackDraft.builder()
                .appointmentId(appointmentId)
                .petId(task.getPetId())
                .storeId(task.getStoreId())
                .employeeId(principal.employeeId())
                .trackId(trackId)
                .pausedSec(pausedSec)
                .points(points)
                .build());
    }
}
