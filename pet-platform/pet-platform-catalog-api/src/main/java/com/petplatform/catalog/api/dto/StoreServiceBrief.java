package com.petplatform.catalog.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 门店在架服务简报（ADR-022，公开浏览 services 内嵌数据源）。
 * durationMin 已按"门店覆盖优先、否则平台默认"合并（与 StoreServiceView 同口径）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreServiceBrief {

    private Long storeId;
    /** cat_store_service.id（= 预约入口的 storeServiceId，ADR-022 §4） */
    private Long storeServiceId;
    private String name;
    /** cat_service.category（C 端主页五分类筛选键源） */
    private String category;
    /** 上架价，单位分 */
    private Long price;
    private Integer durationMin;
}
