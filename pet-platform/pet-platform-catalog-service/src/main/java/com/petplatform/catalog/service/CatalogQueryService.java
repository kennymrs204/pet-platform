package com.petplatform.catalog.service;

import com.petplatform.catalog.api.CatalogQueryApi;
import com.petplatform.catalog.api.dto.StoreServiceView;
import com.petplatform.catalog.domain.StoreServiceEntity;
import com.petplatform.catalog.mapper.StoreServiceMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** {@link CatalogQueryApi} 实现：只读 cat_* 三张表，供 appointment / schedule 跨模块消费。 */
@Service
public class CatalogQueryService implements CatalogQueryApi {

    private final StoreServiceMapper storeServiceMapper;
    private final CatalogAssembler assembler;

    public CatalogQueryService(StoreServiceMapper storeServiceMapper, CatalogAssembler assembler) {
        this.storeServiceMapper = storeServiceMapper;
        this.assembler = assembler;
    }

    @Override
    public StoreServiceView storeServiceOf(Long storeServiceId) {
        if (storeServiceId == null) {
            return null;
        }
        StoreServiceEntity entity = storeServiceMapper.selectById(storeServiceId);
        if (entity == null) {
            return null;
        }
        // 平台服务已删除时装配器会跳过该条——按“不可预约”处理，交由调用方给错误码
        List<StoreServiceView> views = assembler.toStoreServiceViews(List.of(entity));
        return views.isEmpty() ? null : views.get(0);
    }

    @Override
    public java.util.List<com.petplatform.catalog.api.dto.StoreServiceBrief> activeServicesOfStores(
            java.util.Collection<Long> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return List.of();
        }
        return storeServiceMapper.selectActiveBriefsByStores(storeIds).stream()
                .map(com.petplatform.catalog.mapper.StoreServiceMapper.BriefRow::toBrief)
                .toList();
    }
}
