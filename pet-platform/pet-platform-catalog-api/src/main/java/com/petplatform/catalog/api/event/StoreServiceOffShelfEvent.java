package com.petplatform.catalog.api.event;

import java.time.LocalDateTime;

/**
 * 门店服务下架事件（第二阶段 §catalog：供预约侧提示，<b>不强联动</b>）。
 * 存量预约继续履约，仅新的预约创建不再引用该 StoreService（第五阶段 §上下架裁决）。
 */
public record StoreServiceOffShelfEvent(
        Long storeServiceId,
        Long storeId,
        Long serviceId,
        String serviceName,
        LocalDateTime occurredAt
) {
}
