package com.petplatform.appointment.service;

import com.petplatform.catalog.api.dto.StoreServiceView;

/**
 * 取消政策"有效值"的单一来源（S7）：缺省与钳制只写一次，创建时写快照（{@code AppointmentCreateTxService}）
 * 与历史单取消 fallback（{@code AppointmentCancelTxService}）共用——防两处口径漂移。
 *
 * <p>语义与 ADR-023 §5 原实现逐字一致：free_hours 缺省 24；rate 缺省 0、钳 0..100。
 */
final class CancelPolicyDefaults {

    /** 免取消窗口缺省小时（门店行不可读或字段为空时）。 */
    static final int FREE_HOURS = 24;
    /** 扣费比例缺省值（初期恒 0=全额退）。 */
    static final int PENALTY_RATE = 0;

    private CancelPolicyDefaults() {
    }

    static int freeHours(StoreServiceView svc) {
        return svc == null || svc.getCancelFreeHours() == null ? FREE_HOURS : svc.getCancelFreeHours();
    }

    static int penaltyRate(StoreServiceView svc) {
        return svc == null || svc.getCancelPenaltyRate() == null
                ? PENALTY_RATE
                : Math.min(Math.max(svc.getCancelPenaltyRate(), 0), 100);
    }
}
