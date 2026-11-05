package com.petplatform.app.web;

import com.petplatform.boarding.api.BoardingQueryApi;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.common.time.BusinessDates;
import com.petplatform.order.api.OrderStatsApi;
import com.petplatform.order.api.dto.OrderDailyStats;
import com.petplatform.schedule.api.ScheduleQueryApi;
import lombok.Builder;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * B 端首页经营概览（P8 §4.3 GET /store/stats/overview，权限码 store:stats:view：ADMIN/STORE_MANAGER）。
 *
 * 跨域只读聚合放 app 装配层的理由：数据分属 boarding/order/schedule 三个 Owner 模块，
 * app 是唯一可同时依赖多方 api 的组合根（PermissionAssembler/EmployeeIdentityController 先例）；
 * 各模块只暴露自己表上的确定性查询，门店隔离由各 Owner 的 WHERE store_id 强制。
 *
 * 口径（ADR-013 定案）：
 * - 在店宠物 = boa_boarding_stay status ∈ {CHECKED_IN, DAILY_CARE}；
 * - 今日订单 = ord_order created_at ∈ 今日(Asia/Shanghai) 且 status ≠ CANCELLED；
 * - 在岗员工 = sch_schedule work_date=今日 且 status='WORKING'；
 * - 今日收入 = 实收(当日 paid_amount，不含已取消单) − 退款冲减(当日 SUCCESS 退款，不含已取消单)；
 * - 较昨日 = 同日口径分别计算；昨日为 0 → deltaPercent=null（不伪造百分比）。
 */
@RestController
@RequestMapping("/api/v1/store/stats")
public class StoreStatsController {

    private final BoardingQueryApi boardingQueryApi;
    private final OrderStatsApi orderStatsApi;
    private final ScheduleQueryApi scheduleQueryApi;

    public StoreStatsController(BoardingQueryApi boardingQueryApi,
                                OrderStatsApi orderStatsApi,
                                ScheduleQueryApi scheduleQueryApi) {
        this.boardingQueryApi = boardingQueryApi;
        this.orderStatsApi = orderStatsApi;
        this.scheduleQueryApi = scheduleQueryApi;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('perm:store:stats:view')")
    public Result<DashboardOverview> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        LocalDate day = date != null ? date : BusinessDates.today();

        OrderDailyStats today = orderStatsApi.dailyStats(storeId, day);
        OrderDailyStats yesterday = orderStatsApi.dailyStats(storeId, day.minusDays(1));
        long delta = today.netAmount() - yesterday.netAmount();
        Double deltaPercent = yesterday.netAmount() == 0 ? null
                : Math.round(delta * 1000.0 / yesterday.netAmount()) / 10.0;

        return Result.ok(DashboardOverview.builder()
                .date(day)
                .inStorePetCount(boardingQueryApi.countInStore(storeId))
                .todayOrderCount(today.getOrderCount())
                .onDutyStaffCount(scheduleQueryApi.countOnDuty(storeId, day))
                .revenue(Revenue.builder()
                        .todayAmount(today.netAmount())
                        .yesterdayAmount(yesterday.netAmount())
                        .deltaAmount(delta)
                        .deltaPercent(deltaPercent)
                        .build())
                .build());
    }

    private Long requireStoreCtx(AuthPrincipal principal) {
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }

    /** 首页概览聚合视图（金额=分，Long 经全局 Jackson 字符串化）。 */
    @Data
    @Builder
    public static class DashboardOverview {
        private LocalDate date;
        private long inStorePetCount;
        private long todayOrderCount;
        private long onDutyStaffCount;
        private Revenue revenue;
    }

    @Data
    @Builder
    public static class Revenue {
        private long todayAmount;
        private long yesterdayAmount;
        private long deltaAmount;
        /** 昨日为 0 时 null：无基数不造百分比，前端回退展示绝对差额 */
        private Double deltaPercent;
    }
}
