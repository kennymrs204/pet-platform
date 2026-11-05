package com.petplatform.catalog.api;

import com.petplatform.catalog.api.dto.StoreServiceView;

/**
 * StoreService 详情查询（第二阶段 §catalog 暴露 api 原文：
 * “价格/时长/所需技能/所需资源类型/可否指定员工——预约创建与校验的核心输入”）。
 *
 * <p>消费方：appointment（创建预约的 C1~C9 校验与快照落库）、schedule（可约性判定的
 * 时长/资源类型/必需技能）。实现只读 cat_* 三张表，技能名经 employee-api 取回。
 */
public interface CatalogQueryApi {

    /** 按门店上架服务 id 取视图；不存在（或已逻辑删除）返回 null，由调用方决定错误码。 */
    StoreServiceView storeServiceOf(Long storeServiceId);

    /**
     * 批量取门店在架服务简报（ADR-022 公开浏览装配，app 层消费）：
     * cat_store_service.status=ON ∧ cat_service.status=ON ∧ 双方未删；(store_id, id) 升序。
     */
    java.util.List<com.petplatform.catalog.api.dto.StoreServiceBrief> activeServicesOfStores(
            java.util.Collection<Long> storeIds);
}
