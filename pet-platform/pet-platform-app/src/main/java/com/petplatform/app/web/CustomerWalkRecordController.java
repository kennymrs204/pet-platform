package com.petplatform.app.web;

import com.petplatform.app.service.StoreWalkPhotoService;
import com.petplatform.appointment.api.AppointmentQueryApi;
import com.petplatform.appointment.api.dto.AppointmentSelfView;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.fulfillment.api.ServiceRecordQueryApi;
import com.petplatform.fulfillment.api.dto.WalkRecordBrief;
import com.petplatform.fulfillment.api.dto.WalkTrackView;
import lombok.Builder;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * C 端：我这条预约的遛狗服务照片与 GPS 轨迹（只读）。
 *
 * <p>归属判定复用 {@code appointmentQueryApi.selfDetail(userId, appointmentId)}（与 C 端预约详情同一判据）：
 * 别人的预约与不存在的预约统一 10004，不回显存在性。路径上**没有**"按照片 id 取图"的入口，
 * 出参只有本预约履约记录里的 key 及其现签 URL ⇒ 不存在"换照片 id 看别人照片"的面。
 *
 * <p>非遛狗预约 / 尚未完成：没有记录不是错误，返回 {@code hasRecord=false} + 空数组，
 * 前端据此显示自然空态（不生成占位图、不报红）。
 */
@RestController
@RequestMapping("/api/v1/customer/appointments")
public class CustomerWalkRecordController {

    private final AppointmentQueryApi appointmentQueryApi;
    private final ServiceRecordQueryApi recordApi;
    private final StoreWalkPhotoService photoService;

    public CustomerWalkRecordController(AppointmentQueryApi appointmentQueryApi,
                                        ServiceRecordQueryApi recordApi,
                                        StoreWalkPhotoService photoService) {
        this.appointmentQueryApi = appointmentQueryApi;
        this.recordApi = recordApi;
        this.photoService = photoService;
    }

    @GetMapping("/{appointmentId:\\d+}/walk-record")
    @PreAuthorize("hasAuthority('perm:appointment:view:self')")
    public Result<CustomerWalkRecordView> walkRecord(@PathVariable Long appointmentId) {
        AuthPrincipal principal = CurrentUserHolder.require();
        AppointmentSelfView apt = appointmentQueryApi.selfDetail(principal.userId(), appointmentId);
        if (apt == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        WalkRecordBrief rec = recordApi.walkRecordsByAppointments(List.of(appointmentId)).get(appointmentId);
        WalkTrackView track = recordApi.walkTracksByAppointments(List.of(appointmentId)).get(appointmentId);
        if (rec == null) {
            return Result.ok(CustomerWalkRecordView.builder()
                    .appointmentId(appointmentId).hasRecord(false)
                    .photos(List.of()).photoUrls(List.of()).track(track).build());
        }
        List<String> photos = rec.getPhotos() == null ? List.of() : rec.getPhotos();
        return Result.ok(CustomerWalkRecordView.builder()
                .appointmentId(appointmentId).hasRecord(true)
                .finishedAt(rec.getFinishedAt()).durationMin(rec.getDurationMin())
                .photos(photos).photoUrls(photoService.signPhotos(photos))
                .track(track).build());
    }

    /**
     * 顾客可见范围：照片 + 完成时间/时长 + 本次轨迹摘要。
     * 刻意不含执行人 id、代点标记与门店内部备注——它们属门店侧履约信息，不是"我的服务记录"所需字段。
     * 轨迹只含本次预约的时间窗与坐标序列，不提供任何"按员工查历史位置"的入口。
     */
    @Data
    @Builder
    public static class CustomerWalkRecordView {
        private Long appointmentId;
        private boolean hasRecord;
        private LocalDateTime finishedAt;
        private Integer durationMin;
        /** 对象 key（不透出存储实现细节之外的信息）；无照片为空数组 */
        private List<String> photos;
        /** 与 photos 同序的 presigned GET（600s）；存储未启用时为空数组 */
        private List<String> photoUrls;
        /** GPS 轨迹摘要（距离/实际记录时长/点数/抽稀路线）；null=本次未采集轨迹 */
        private WalkTrackView track;
    }
}
