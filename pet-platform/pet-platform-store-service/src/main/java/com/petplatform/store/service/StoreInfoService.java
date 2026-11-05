package com.petplatform.store.service;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.store.api.StoreInfoApi;
import com.petplatform.store.api.dto.StoreInfoUpdate;
import com.petplatform.store.api.dto.StoreInfoView;
import com.petplatform.store.domain.StoreEntity;
import com.petplatform.store.mapper.StoreInfoMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 门店资料读写实现（ADR-020 §3：跨字段校验在此，注解校验在 web 层 @Valid）。 */
@Service
public class StoreInfoService implements StoreInfoApi {

    private final StoreInfoMapper storeMapper;

    public StoreInfoService(StoreInfoMapper storeMapper) {
        this.storeMapper = storeMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public StoreInfoView info(Long storeId) {
        StoreEntity store = storeId == null ? null : storeMapper.selectById(storeId);
        return store == null ? null : toView(store);
    }

    @Override
    @Transactional
    public StoreInfoView update(Long storeId, StoreInfoUpdate cmd) {
        if (storeId == null || storeId == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少门店上下文");
        }
        if (storeMapper.selectById(storeId) == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        if (!cmd.getBusinessStartTime().isBefore(cmd.getBusinessEndTime())) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "营业开始时间必须早于结束时间");
        }
        boolean breakStartGiven = cmd.getBreakStartTime() != null;
        boolean breakEndGiven = cmd.getBreakEndTime() != null;
        if (breakStartGiven != breakEndGiven) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "休息时段起止必须成对填写");
        }
        if (breakStartGiven && !cmd.getBreakStartTime().isBefore(cmd.getBreakEndTime())) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "休息开始时间必须早于休息结束时间");
        }
        // 午休落在营业窗之外不是"没效果"就是"误导"：槽位生成按 break 过滤，窗外那段本来就不可约。
        if (breakStartGiven && (cmd.getBreakStartTime().isBefore(cmd.getBusinessStartTime())
                || cmd.getBreakEndTime().isAfter(cmd.getBusinessEndTime()))) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "休息时段须落在营业时间内");
        }
        if (storeMapper.updateInfo(storeId, cmd) == 0) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        return info(storeId);
    }

    private StoreInfoView toView(StoreEntity s) {
        return StoreInfoView.builder()
                .id(s.getId()).name(s.getName()).phone(s.getPhone()).address(s.getAddress())
                .latitude(s.getLatitude()).longitude(s.getLongitude())
                .businessStartTime(s.getBusinessStartTime()).businessEndTime(s.getBusinessEndTime())
                .breakStartTime(s.getBreakStartTime()).breakEndTime(s.getBreakEndTime())
                .status(s.getStatus()).storeType(s.getStoreType()).announcement(s.getAnnouncement())
                .build();
    }
}
