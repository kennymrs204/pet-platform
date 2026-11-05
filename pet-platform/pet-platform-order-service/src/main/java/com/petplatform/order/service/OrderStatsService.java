package com.petplatform.order.service;

import com.petplatform.common.time.BusinessDates;
import com.petplatform.order.api.OrderStatsApi;
import com.petplatform.order.api.dto.OrderDailyStats;
import com.petplatform.order.mapper.OrderStatsMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class OrderStatsService implements OrderStatsApi {

    private final OrderStatsMapper mapper;

    public OrderStatsService(OrderStatsMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public OrderDailyStats dailyStats(Long storeId, LocalDate date) {
        LocalDateTime from = BusinessDates.dayStart(date);
        LocalDateTime to = BusinessDates.dayEndExclusive(date);
        return OrderDailyStats.builder()
                .orderCount(mapper.countOrders(storeId, from, to))
                .paidAmount(mapper.sumPaid(storeId, from, to))
                .refundAmount(mapper.sumRefund(storeId, from, to))
                .build();
    }

    @Override
    public java.util.Map<Long, Long> paidAmountByUsers(Long storeId, java.util.Collection<Long> userIds) {
        if (storeId == null || storeId == 0L || userIds == null || userIds.isEmpty()) {
            return java.util.Map.of();
        }
        return mapper.selectPaidByUsers(storeId, userIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        OrderStatsMapper.UserPaidRow::getUserId,
                        OrderStatsMapper.UserPaidRow::getPaid,
                        (a, b) -> a));
    }
}
