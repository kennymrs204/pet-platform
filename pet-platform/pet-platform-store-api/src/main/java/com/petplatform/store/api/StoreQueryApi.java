package com.petplatform.store.api;

import com.petplatform.store.api.dto.StoreView;

/**
 * 门店只读查询（表 Owner：store 模块，sto_store）。
 * appointment 创建 C3 校验与可约候选槽（营业时间/休息段）的唯一入口（ADR-016 §9）。
 */
public interface StoreQueryApi {

    /** 门店视图；不存在/已删返回 null。 */
    StoreView store(Long storeId);

    /**
     * 公开浏览列表（ADR-022）：status=OPEN ∧ store_type≠PLATFORM_HQ ∧ deleted=0，
     * keyword 对 name/address LIKE（空=全量），id 升序。
     */
    java.util.List<com.petplatform.store.api.dto.PublicStoreView> publicStores(String keyword);

    /** 公开浏览单店（同口径，不可见返回 null 由调用方按 10004）。 */
    com.petplatform.store.api.dto.PublicStoreView publicStore(Long storeId);

    /** 批量店名（ADR-012 遗留：employee-identities 的 storeName 补值，装配层消费）。缺失 id 静默跳过。 */
    java.util.Map<Long, String> storeNames(java.util.Collection<Long> storeIds);
}
