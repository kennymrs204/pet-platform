package com.petplatform.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.result.PageResult;
import com.petplatform.order.api.StoreOrderQueryApi;
import com.petplatform.order.api.dto.StoreOrderView;
import com.petplatform.order.domain.OrderEntity;
import com.petplatform.order.mapper.StoreOrderQueryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 门店订单只读查询实现（ADR-019）。排序确定性：created_at desc → id desc。 */
@Slf4j
@Service
public class StoreOrderQueryService implements StoreOrderQueryApi {

    private final StoreOrderQueryMapper orderMapper;
    private final ObjectMapper json;

    public StoreOrderQueryService(StoreOrderQueryMapper orderMapper, ObjectMapper json) {
        this.orderMapper = orderMapper;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<StoreOrderView> pageInStore(Long storeId, String status, LocalDate dateFrom, LocalDate dateTo,
                                                 String keyword, int pageNum, int pageSize) {
        if (storeId == null || storeId == 0L) {
            return PageResult.of(0L, 1, Math.max(pageSize, 1), List.of());
        }
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), 100);
        // 前缀匹配（LIKE 'kw%' 走索引）；web 层已拦 <2 字符，这里防御性兜底为不过滤
        String prefix = keyword == null || keyword.isBlank() || keyword.trim().length() < 2
                ? null : keyword.trim();
        var mpPage = orderMapper.selectPage(
                Page.of(page, size),
                Wrappers.<OrderEntity>lambdaQuery()
                        .eq(OrderEntity::getStoreId, storeId)
                        .eq(status != null && !status.isBlank(), OrderEntity::getStatus, status)
                        .likeRight(prefix != null, OrderEntity::getOrderNo, prefix)
                        .ge(dateFrom != null, OrderEntity::getCreatedAt,
                                dateFrom == null ? null : dateFrom.atStartOfDay())
                        .lt(dateTo != null, OrderEntity::getCreatedAt,
                                dateTo == null ? null : dateTo.plusDays(1).atStartOfDay())
                        .orderByDesc(OrderEntity::getCreatedAt)
                        .orderByDesc(OrderEntity::getId));
        List<StoreOrderView> list = mpPage.getRecords().stream().map(this::toView).toList();
        return PageResult.of(mpPage.getTotal(), page, size, list);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<StoreOrderView> pageInStoreByUser(Long storeId, Long userId, int pageNum, int pageSize) {
        if (storeId == null || storeId == 0L || userId == null || userId == 0L) {
            return PageResult.of(0L, 1, Math.max(pageSize, 1), List.of());
        }
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), 100);
        var mpPage = orderMapper.selectPage(
                Page.of(page, size),
                Wrappers.<OrderEntity>lambdaQuery()
                        .eq(OrderEntity::getStoreId, storeId)
                        .eq(OrderEntity::getUserId, userId)
                        .orderByDesc(OrderEntity::getCreatedAt)
                        .orderByDesc(OrderEntity::getId));
        List<StoreOrderView> list = mpPage.getRecords().stream().map(this::toView).toList();
        return PageResult.of(mpPage.getTotal(), page, size, list);
    }

    @Override
    @Transactional(readOnly = true)
    public StoreOrderView detailInStore(Long storeId, Long orderId) {
        if (storeId == null || storeId == 0L || orderId == null) {
            return null;
        }
        OrderEntity order = orderMapper.selectOne(Wrappers.<OrderEntity>lambdaQuery()
                .eq(OrderEntity::getId, orderId)
                .eq(OrderEntity::getStoreId, storeId)
                .last("LIMIT 1"));
        return order == null ? null : toView(order);
    }

    private StoreOrderView toView(OrderEntity o) {
        Map<String, Object> snapshot = readSnapshot(o.getItemSnapshot());
        return StoreOrderView.builder()
                .id(o.getId()).orderNo(o.getOrderNo()).appointmentId(o.getAppointmentId())
                .userId(o.getUserId()).status(o.getStatus())
                .amount(o.getAmount()).paidAmount(o.getPaidAmount()).paymentMethod(o.getPaymentMethod())
                .payExpireAt(o.getPayExpireAt()).paidAt(o.getPaidAt()).createdAt(o.getCreatedAt())
                .serviceName(text(snapshot.get("serviceName")))
                .durationMin(snapshot.get("durationMin") instanceof Number n ? n.intValue() : null)
                .build();
    }

    private Map<String, Object> readSnapshot(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("item_snapshot 解析失败降级空图: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
