package com.petplatform.store.service;

import com.petplatform.store.api.StoreQueryApi;
import com.petplatform.store.api.dto.StoreView;
import com.petplatform.store.mapper.StoreQueryMapper;
import org.springframework.stereotype.Service;

@Service
public class StoreQueryService implements StoreQueryApi {

    private final StoreQueryMapper mapper;

    public StoreQueryService(StoreQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public StoreView store(Long storeId) {
        if (storeId == null) {
            return null;
        }
        StoreQueryMapper.StoreViewRow row = mapper.selectStore(storeId);
        return row == null ? null : row.toView();
    }

    @Override
    public java.util.List<com.petplatform.store.api.dto.PublicStoreView> publicStores(String keyword) {
        return mapper.selectPublicStores(keyword == null || keyword.isBlank() ? null : keyword.trim())
                .stream().map(StoreQueryMapper.PublicStoreRow::toView).toList();
    }

    @Override
    public com.petplatform.store.api.dto.PublicStoreView publicStore(Long storeId) {
        if (storeId == null) {
            return null;
        }
        StoreQueryMapper.PublicStoreRow row = mapper.selectPublicStore(storeId);
        return row == null ? null : row.toView();
    }

    @Override
    public java.util.Map<Long, String> storeNames(java.util.Collection<Long> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Long, String> out = new java.util.HashMap<>();
        mapper.selectNamesByIds(storeIds).forEach(r -> out.put(r.getId(), r.getName()));
        return out;
    }
}
