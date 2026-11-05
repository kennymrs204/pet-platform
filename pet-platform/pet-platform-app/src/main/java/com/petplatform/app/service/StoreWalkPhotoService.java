package com.petplatform.app.service;

import com.petplatform.appointment.api.AppointmentTaskApi;
import com.petplatform.appointment.api.dto.AppointmentTaskView;
import com.petplatform.catalog.api.CatalogQueryApi;
import com.petplatform.catalog.api.dto.StoreServiceView;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.storage.MediaStorage;
import com.petplatform.fulfillment.api.ServiceRecordCommandApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 遛狗照片关联（2026-10-24）。<b>独立写入口</b>，不塞进"完成服务"：
 * 完成动作已经承担"预约状态 + 履约记录同生同死"，再挂上传会让图片失败连带状态回滚；
 * 这里只改 {@code ful_walk_record.photos} 一列，预约状态一字不碰。
 *
 * <p>授权三层，缺一不可：
 * ① 门店与人员：{@link AppointmentTaskApi#taskOf} —— 与本店"开始/完成服务"同一套判据
 *   （店长/前台可操作本店；执行人只能操作派给自己的），跨店 10004、非执行人 10003；
 * ② 业务类型：只认目录里的 {@code DOG_WALK}（判据与 TaskCompletionService 同源，不看服务名快照）；
 * ③ 状态：仅 {@code COMPLETED}（照片属于"已经发生的服务记录"，未完成时无记录可关联）。
 *
 * <p>文件层沿用 ADR-029/聊天图片的既有规矩：只收 key，不存二进制；key 必须形态合法、场景=WALK_PHOTO、
 * 桶主=当前登录用户，并且**对象真实存在**（HEAD 回查 + 大小/类型复核）——自填 key 的出口必须校归属，
 * 否则等于让提交者决定"替谁的对象签名"。
 */
@Slf4j
@Service
public class StoreWalkPhotoService {

    private static final String CODE_WALK = "DOG_WALK";

    private final AppointmentTaskApi appointmentTaskApi;
    private final CatalogQueryApi catalogApi;
    private final ServiceRecordCommandApi recordApi;
    private final ObjectProvider<MediaStorage> media;
    private final long maxImageBytes;

    public StoreWalkPhotoService(AppointmentTaskApi appointmentTaskApi, CatalogQueryApi catalogApi,
                                 ServiceRecordCommandApi recordApi, ObjectProvider<MediaStorage> media,
                                 @Value("${pet.storage.max-size-mb:5}") long maxSizeMb) {
        this.appointmentTaskApi = appointmentTaskApi;
        this.catalogApi = catalogApi;
        this.recordApi = recordApi;
        this.media = media;
        this.maxImageBytes = maxSizeMb * 1024 * 1024;
    }

    /**
     * 给已完成的遛狗记录追加照片。
     *
     * @param mediaKeys 本次新增的对象 key（1..{@link ServiceRecordCommandApi#MAX_WALK_PHOTOS} 张）
     * @return 追加后的完整 key 列表 + 现签 GET URL（同序）
     */
    @Transactional
    public WalkPhotosView add(Long storeId, AuthPrincipal principal, Long appointmentId, List<String> mediaKeys) {
        boolean storeWide = principal.permissions().contains("appointment:view:store");
        AppointmentTaskView task = appointmentTaskApi.taskOf(appointmentId, storeId,
                principal.employeeId(), storeWide);
        if (!"COMPLETED".equals(task.getStatus())) {
            throw new BusinessException(com.petplatform.appointment.api.AppointmentErrorCode.ILLEGAL_STATE_TRANSITION,
                    "仅已完成的遛狗服务可添加照片");
        }
        StoreServiceView svc = catalogApi.storeServiceOf(task.getStoreServiceId());
        String code = svc == null ? null : svc.getServiceCode();
        if (!CODE_WALK.equals(code)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "非遛狗服务，不能添加遛狗照片");
        }
        requireValidKeys(principal.userId(), mediaKeys);
        List<String> photos = recordApi.appendWalkPhotos(appointmentId, mediaKeys);
        return new WalkPhotosView(appointmentId, photos, signPhotos(photos));
    }

    /** 是否可读照片由业务端点负责；这里只把 key 换成 600s 的 GET URL（存储未启用则空数组）。 */
    public List<String> signPhotos(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        MediaStorage storage = media.getIfAvailable();
        if (storage == null) {
            return List.of();
        }
        return keys.stream()
                .map(k -> storage.presignGet(k, MediaStorage.GET_TTL))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * key 三校 + 对象回查（与聊天图片 P7.1 的口径一致）：
     * 形态 → 场景 → 桶主，再 HEAD 确认对象存在且大小/类型可信。失败一律 10001（不泄露他人 key 是否存在）。
     */
    private void requireValidKeys(Long userId, List<String> mediaKeys) {
        if (mediaKeys == null || mediaKeys.isEmpty()) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "至少选择一张照片");
        }
        if (mediaKeys.size() > ServiceRecordCommandApi.MAX_WALK_PHOTOS) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID,
                    "单次最多上传 " + ServiceRecordCommandApi.MAX_WALK_PHOTOS + " 张照片");
        }
        MediaStorage storage = media.getIfAvailable();
        if (storage == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "图片存储未启用，无法关联照片");
        }
        for (String raw : mediaKeys) {
            String key = raw == null ? "" : raw.trim();
            if (!MediaStorage.isValidKey(key)) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "照片 key 形态非法");
            }
            if (!MediaStorage.SCENE_WALK_PHOTO.equals(MediaStorage.keyScene(key))) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "照片场景非法");
            }
            if (!Objects.equals(userId, MediaStorage.keyOwner(key))) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "照片不属于当前账号");
            }
            requireStoredImage(storage, key);
        }
    }

    private void requireStoredImage(MediaStorage storage, String key) {
        MediaStorage.StoredObject obj;
        try {
            obj = storage.stat(key);
        } catch (RuntimeException e) {
            throw new BusinessException(CommonErrorCode.SYSTEM_ERROR, "图片存储暂不可用，请稍后重试");
        }
        if (obj == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "照片未上传成功，请重新选择图片");
        }
        if (obj.size() <= 0) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "照片文件为空，请重新选择图片");
        }
        if (obj.size() > maxImageBytes) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID,
                    "照片超过大小上限（" + (maxImageBytes / 1024 / 1024) + "MB）");
        }
        if (obj.contentType() == null || !obj.contentType().startsWith("image/")) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "仅支持图片文件");
        }
    }

    /** B 端关联照片的响应：key 列表 + 现签 URL（同序；签不出为 null 由前端回退占位）。 */
    public record WalkPhotosView(Long appointmentId, List<String> photos, List<String> photoUrls) {
    }
}
