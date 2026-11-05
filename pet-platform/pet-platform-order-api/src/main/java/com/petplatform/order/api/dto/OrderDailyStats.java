package com.petplatform.order.api.dto;

import lombok.Builder;
import lombok.Data;

/** 门店单日经营统计（金额单位=分，BIGINT 全链路，ADR-006-9；口径见 ADR-013）。 */
@Data
@Builder
public class OrderDailyStats {

    /** 今日新增有效订单数（created_at 当日，不含 CANCELLED） */
    private long orderCount;
    /** 实收合计：paid_at 当日且订单未取消 的 paid_amount 之和 */
    private long paidAmount;
    /** 退款冲减：refunded_at 当日、退款单 SUCCESS 且订单未取消 的退款额之和 */
    private long refundAmount;

    /** 净收入 = 实收 − 退款冲减（口径自洽：paid_then_cancelled 单不计收也不冲减）。 */
    public long netAmount() {
        return paidAmount - refundAmount;
    }
}
