package com.petplatform.schedule.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.time.BusinessDates;
import com.petplatform.schedule.api.ResourceErrorCode;
import com.petplatform.schedule.api.StoreResourceApi;
import com.petplatform.schedule.api.dto.KennelResourceView;
import com.petplatform.schedule.domain.ResourceEntity;
import com.petplatform.schedule.mapper.ResourceOccupancyMapper;
import com.petplatform.schedule.mapper.StoreResourceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/** 门店资源台账实现（ADR-020 §4）。 */
@Service
public class StoreResourceService implements StoreResourceApi {

    /** 与 sch_resource.type 注释一致（V1:264）；内联枚举，无字典表。 */
    private static final Set<String> TYPES = Set.of(
            "BATH_STATION", "GROOMING_STATION", "EXAM_ROOM",
            "KENNEL_SMALL", "KENNEL_MEDIUM", "KENNEL_LARGE", "CAT_ROOM");
    private static final Set<String> STATUSES = Set.of("AVAILABLE", "MAINTENANCE", "DISABLED");

    private final StoreResourceMapper resourceMapper;
    private final ResourceOccupancyMapper occupancyMapper;

    public StoreResourceService(StoreResourceMapper resourceMapper, ResourceOccupancyMapper occupancyMapper) {
        this.resourceMapper = resourceMapper;
        this.occupancyMapper = occupancyMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<KennelResourceView> listInStore(Long storeId) {
        if (storeId == null || storeId == 0L) {
            return List.of();
        }
        return resourceMapper.selectList(Wrappers.<ResourceEntity>lambdaQuery()
                        .eq(ResourceEntity::getStoreId, storeId)
                        .orderByAsc(ResourceEntity::getType)
                        .orderByAsc(ResourceEntity::getId))
                .stream().map(StoreResourceService::toView).toList();
    }

    @Override
    @Transactional
    public KennelResourceView create(Long storeId, String type, String name) {
        if (storeId == null || storeId == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少门店上下文");
        }
        if (type == null || !TYPES.contains(type)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "资源类型非法");
        }
        requireName(name);
        ResourceEntity entity = new ResourceEntity();
        entity.setStoreId(storeId);
        entity.setType(type);
        entity.setName(name.trim());
        entity.setStatus("AVAILABLE");
        resourceMapper.insert(entity);
        return toView(entity);
    }

    @Override
    @Transactional
    public KennelResourceView update(Long storeId, Long resourceId, String name, String status) {
        if (resourceId == null || storeId == null || storeId == 0L) {
            return null;
        }
        boolean hasName = name != null && !name.isBlank();
        boolean hasStatus = status != null && !status.isBlank();
        // 与分配路径共用同一条资源行锁：锁释放前完成占用检查与更新，杜绝检查/写入之间的竞态。
        ResourceEntity entity = resourceMapper.selectForUpdate(storeId, resourceId);
        if (entity == null) {
            return null;
        }
        if (hasName) {
            requireName(name);
        }
        if (hasStatus && !STATUSES.contains(status)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "资源状态非法");
        }
        boolean turningOff = hasStatus && "AVAILABLE".equals(entity.getStatus()) && !"AVAILABLE".equals(status);
        if (turningOff
                && occupancyMapper.selectReservationIdFromForUpdate(resourceId, BusinessDates.today()) != null) {
            throw BusinessException.of(ResourceErrorCode.RESOURCE_IN_USE);
        }
        if (hasName) {
            entity.setName(name.trim());
        }
        if (hasStatus) {
            entity.setStatus(status);
        }
        resourceMapper.updateById(entity);
        return toView(entity);
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "资源名称不能为空");
        }
        if (name.trim().length() > 50) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "资源名称最多50字");
        }
    }

    private static KennelResourceView toView(ResourceEntity e) {
        return KennelResourceView.builder()
                .id(e.getId()).storeId(e.getStoreId()).type(e.getType())
                .name(e.getName()).status(e.getStatus())
                .build();
    }
}
