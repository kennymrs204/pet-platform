package com.petplatform.catalog.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.petplatform.catalog.api.dto.StoreServiceQuery;
import com.petplatform.catalog.api.dto.StoreServiceView;
import com.petplatform.catalog.domain.ServiceEntity;
import com.petplatform.catalog.domain.StoreServiceEntity;
import com.petplatform.catalog.mapper.ServiceMapper;
import com.petplatform.catalog.mapper.StoreServiceMapper;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * C 端门店价目表浏览（第八阶段 §4.2 GET /api/v1/customer/store-services?storeId=）。
 * 状态强制 ON：下架服务对 C 端不可见（第五阶段 §上下架：存量预约仍可履约，但不再可约）。
 */
@Service
public class CustomerCatalogService {

    private final StoreServiceMapper storeServiceMapper;
    private final ServiceMapper serviceMapper;
    private final CatalogAssembler assembler;

    public CustomerCatalogService(StoreServiceMapper storeServiceMapper, ServiceMapper serviceMapper,
                                 CatalogAssembler assembler) {
        this.storeServiceMapper = storeServiceMapper;
        this.serviceMapper = serviceMapper;
        this.assembler = assembler;
    }

    public PageResult<StoreServiceView> page(StoreServiceQuery query) {
        if (query.getStoreId() == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "storeId 必填");
        }
        LambdaQueryWrapper<StoreServiceEntity> wrapper = Wrappers.<StoreServiceEntity>lambdaQuery()
                .eq(StoreServiceEntity::getStoreId, query.getStoreId())
                .eq(StoreServiceEntity::getStatus, "ON")
                .orderByAsc(StoreServiceEntity::getId);
        String keyword = query.getKeyword();
        if (StringUtils.hasText(keyword)) {
            List<Long> serviceIds = serviceMapper.selectList(Wrappers.<ServiceEntity>lambdaQuery()
                            .select(ServiceEntity::getId)
                            .and(w -> w.like(ServiceEntity::getName, keyword).or().like(ServiceEntity::getCode, keyword)))
                    .stream()
                    .map(ServiceEntity::getId)
                    .toList();
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
}
