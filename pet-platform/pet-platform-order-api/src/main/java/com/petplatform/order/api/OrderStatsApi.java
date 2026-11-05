package com.petplatform.order.api;

import com.petplatform.order.api.dto.OrderDailyStats;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;

/**
 * 订单统计只读查询（表 Owner：order 模块，含 ord_refund）。首页「今日订单/今日收入」数据源。
 * 口径定案见 ADR-013；日期窗口由实现层按业务时区（Asia/Shanghai）换算，调用方只传日期。
 */
public interface OrderStatsApi {

    OrderDailyStats dailyStats(Long storeId, LocalDate date);

    /**
     * 本店按客户聚合的**累计实付**（分），ADR-017 §6 增量。
     * 口径 = SUM(paid_amount) WHERE paid_at IS NOT NULL ∧ status ≠ CANCELLED，按 user_id 分组；
     * **不扣退款**（首页"今日收入"是净额口径，两者语义不同，字段名 totalPaidAmount 即"实付"）。
     * 无实付记录的客户不出现在 Map 中（调用方按 0 处理）；userIds 为空返回空 Map。
     */
    Map<Long, Long> paidAmountByUsers(Long storeId, Collection<Long> userIds);
}
