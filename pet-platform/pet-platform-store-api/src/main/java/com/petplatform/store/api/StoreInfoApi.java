package com.petplatform.store.api;

import com.petplatform.store.api.dto.StoreInfoUpdate;
import com.petplatform.store.api.dto.StoreInfoView;

/**
 * 门店资料读写（ADR-020；表 Owner=store，sto_store）。
 * 与 ADR-016 的 {@code StoreQueryApi}（只给可约性判断用的最小子集）分工不同：本接口面向门店自助设置，字段全。
 */
public interface StoreInfoApi {

    /** 门店资料全视图；不存在/已删返回 null。 */
    StoreInfoView info(Long storeId);

    /**
     * 更新白名单字段（ADR-020 §3：store_type/status 不可写）。
     * storeId 由调用方从 X-Store-Id 上下文取，请求体内没有该字段 → 无跨店注入面。
     */
    StoreInfoView update(Long storeId, StoreInfoUpdate cmd);
}
