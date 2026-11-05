package com.petplatform.catalog.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.petplatform.catalog.api.dto.StoreServiceQuery;
import com.petplatform.catalog.api.dto.StoreServiceUpsertRequest;
import com.petplatform.catalog.api.dto.StoreServiceView;
import com.petplatform.catalog.api.event.StoreServiceOffShelfEvent;
import com.petplatform.catalog.domain.ServiceEntity;
import com.petplatform.catalog.domain.StoreServiceEntity;
import com.petplatform.catalog.mapper.ServiceMapper;
import com.petplatform.catalog.mapper.StoreServiceMapper;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 门店上架服务维护（第八阶段 §4.3 /api/v1/store/store-services，B 端）。
 *
 * <p>storeId 一律取自 X-Store-Id 上下文而非入参（ADR-008 §1.3 数据范围：
 * 防越权改别店价目表）。上下架不强联动存量预约，只发事件供预约侧提示。
 */
@Service
public class StoreCatalogService {

    /** 遛狗服务码（户外履约，不占实体资源；判据只认 cat_service.code，不看名称）。 */
    private static final String CODE_WALK = "DOG_WALK";

    private final StoreServiceMapper storeServiceMapper;
    private final ServiceMapper serviceMapper;
    private final CatalogAssembler assembler;
    private final ApplicationEventPublisher events;

    public StoreCatalogService(StoreServiceMapper storeServiceMapper, ServiceMapper serviceMapper,
                               CatalogAssembler assembler, ApplicationEventPublisher events) {
        this.storeServiceMapper = storeServiceMapper;
        this.serviceMapper = serviceMapper;
        this.assembler = assembler;
        this.events = events;
    }

    public PageResult<StoreServiceView> page(StoreServiceQuery query) {
        Long storeId = currentStoreId();
        LambdaQueryWrapper<StoreServiceEntity> wrapper = Wrappers.<StoreServiceEntity>lambdaQuery()
                .eq(StoreServiceEntity::getStoreId, storeId)
                .eq(StringUtils.hasText(query.getStatus()), StoreServiceEntity::getStatus, query.getStatus())
                .orderByAsc(StoreServiceEntity::getId);
        String keyword = query.getKeyword();
        if (StringUtils.hasText(keyword)) {
            List<Long> serviceIds = serviceIdsByKeyword(keyword);
            if (serviceIds.isEmpty()) {
                return PageResult.of(0, query.getPageNum(), query.getPageSize(), List.of());
            }
            wrapper.in(StoreServiceEntity::getServiceId, serviceIds);
        }
        Page<StoreServiceEntity> page = storeServiceMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.getTotal(), query.getPageNum(), query.getPageSize(),
                assembler.toStoreServiceViews(page.getRecords()));
    }

    /**
     * 代客建单的可订清单：只读、只出当前门店 ON 上架行（店范围仍只认 X-Store-Id）。
     * 与价目维护共用读模型，但调用方只需 appointment:create，不因此获得 manage 能力。
     */
    public PageResult<StoreServiceView> bookable() {
        StoreServiceQuery query = new StoreServiceQuery();
        query.setStatus("ON");
        query.setPageSize(50);
        return page(query);
    }

    @Transactional
    public StoreServiceView create(StoreServiceUpsertRequest request) {
        Long storeId = currentStoreId();
        requireDurationAligned(request.getDurationMin());
        ServiceEntity service = serviceMapper.selectById(request.getServiceId());
        if (service == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "平台服务不存在");
        }
        if (!"ON".equals(service.getStatus())) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "平台服务已下线，无法上架");
        }
        long duplicated = storeServiceMapper.selectCount(Wrappers.<StoreServiceEntity>lambdaQuery()
                .eq(StoreServiceEntity::getStoreId, storeId)
                .eq(StoreServiceEntity::getServiceId, request.getServiceId()));
        if (duplicated > 0) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "该服务本店已上架");
        }
        StoreServiceEntity entity = new StoreServiceEntity();
        entity.setStoreId(storeId);
        entity.setServiceId(request.getServiceId());
        applyTo(entity, request, service.getCode());
        entity.setStatus(StringUtils.hasText(request.getStatus()) ? request.getStatus() : "ON");
        try {
            storeServiceMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "该服务本店已上架");
        }
        return first(assembler.toStoreServiceViews(List.of(entity)));
    }

    @Transactional
    public StoreServiceView update(Long id, StoreServiceUpsertRequest request) {
        Long storeId = currentStoreId();
        StoreServiceEntity entity = requireOwned(id, storeId);
        if (!entity.getServiceId().equals(request.getServiceId())) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "上架记录不支持更换服务，请下架后重新上架");
        }
        requireDurationAligned(request.getDurationMin());
        ServiceEntity service = serviceMapper.selectById(entity.getServiceId());
        String oldStatus = entity.getStatus();
        applyTo(entity, request, service == null ? null : service.getCode());
        if (StringUtils.hasText(request.getStatus())) {
            entity.setStatus(request.getStatus());
        }
        storeServiceMapper.updateById(entity);
        if ("ON".equals(oldStatus) && "OFF".equals(entity.getStatus())) {
            events.publishEvent(new StoreServiceOffShelfEvent(entity.getId(), storeId, entity.getServiceId(),
                    service == null ? null : service.getName(), LocalDateTime.now()));
        }
        return first(assembler.toStoreServiceViews(List.of(entity)));
    }

    private StoreServiceEntity requireOwned(Long id, Long storeId) {
        StoreServiceEntity entity = storeServiceMapper.selectById(id);
        if (entity == null || !entity.getStoreId().equals(storeId)) {
            // 跨店与不存在同码：不回显存在性，避免门店 id 探测
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        return entity;
    }

    private Long currentStoreId() {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }

    /**
     * 上架字段落库。
     *
     * <p><b>遛狗不占实体资源</b>：{@code DOG_WALK} 的 {@code required_resource_type} 强制归一为 NULL
     * （V1 既有约定：NULL=无需资源）。预约侧（可约窗口/创建/改期）看到非空类型就会去抢台位——
     * 遛狗被写成 {@code BATH_STATION} 会直接变成"没有美容台就约不了遛狗"。
     * <b>这里选择归一而不是报 10001</b>：本接口没有"清空资源类型"的专用入口，若历史行已被写成台位，
     * 拒绝会让该行既不能改价也不能自救（自锁）；归一化在下次保存时顺手修复数据。
     * 其余服务（洗澡/美容/诊疗/托管）完全不受影响，仍按请求原值落库。
     */
    private void applyTo(StoreServiceEntity entity, StoreServiceUpsertRequest request, String serviceCode) {
        entity.setPrice(request.getPrice());
        entity.setDurationMin(request.getDurationMin());
        entity.setRequiredResourceType(CODE_WALK.equals(serviceCode) ? null : request.getRequiredResourceType());
        entity.setAllowUserPickEmployee(request.getAllowUserPickEmployee());
        entity.setCancelFreeHours(request.getCancelFreeHours());
        entity.setCancelPenaltyRate(request.getCancelPenaltyRate());
    }

    private void requireDurationAligned(Integer durationMin) {
        if (durationMin % AdminCatalogService.SLOT_MIN != 0) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "服务时长须为 30 分钟的整数倍");
        }
    }

    /** 关键字命中服务名/编码时换算成 serviceId 集合（同模块内换算，不穿透别模块）。 */
    private List<Long> serviceIdsByKeyword(String keyword) {
        return serviceMapper.selectList(Wrappers.<ServiceEntity>lambdaQuery()
                        .select(ServiceEntity::getId)
                        .and(w -> w.like(ServiceEntity::getName, keyword).or().like(ServiceEntity::getCode, keyword)))
                .stream()
                .map(ServiceEntity::getId)
                .toList();
    }

    private StoreServiceView first(List<StoreServiceView> views) {
        if (views.isEmpty()) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        return views.get(0);
    }
}
