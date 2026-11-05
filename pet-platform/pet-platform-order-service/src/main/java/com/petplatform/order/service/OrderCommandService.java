package com.petplatform.order.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.order.api.OrderCommandApi;
import com.petplatform.order.api.OrderErrorCode;
import com.petplatform.order.api.OrderQueryApi;
import com.petplatform.order.api.dto.CreateOrderCommand;
import com.petplatform.order.api.dto.OrderView;
import com.petplatform.order.api.event.OrderPaidEvent;
import com.petplatform.order.api.event.OrderTimeoutCancelledEvent;
import com.petplatform.order.api.event.RefundAppliedEvent;
import com.petplatform.order.domain.OrderEntity;
import com.petplatform.order.domain.RefundEntity;
import com.petplatform.order.mapper.OrderMapper;
import com.petplatform.order.mapper.RefundMapper;
import com.petplatform.common.result.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 订单命令+查询（表 Owner=order，ADR-016）。金额全程分（Long），快照 JSON 落 item_snapshot。
 */
@Slf4j
@Service
public class OrderCommandService implements OrderCommandApi, OrderQueryApi {

    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final OrderMapper orderMapper;
    private final RefundMapper refundMapper;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;

    public OrderCommandService(OrderMapper orderMapper, RefundMapper refundMapper,
                               ApplicationEventPublisher events, ObjectMapper json) {
        this.orderMapper = orderMapper;
        this.refundMapper = refundMapper;
        this.events = events;
        this.json = json;
    }

    @Override
    @Transactional
    public OrderView createForAppointment(CreateOrderCommand cmd) {
        OrderEntity order = new OrderEntity();
        order.setOrderNo("OD" + LocalDateTime.now().format(NO_DATE) + IdWorker.getId());
        order.setAppointmentId(cmd.getAppointmentId());
        order.setUserId(cmd.getUserId());
        order.setStoreId(cmd.getStoreId());
        order.setAmount(cmd.getAmount());
        order.setDiscountAmount(0L);
        order.setPaidAmount(0L);
        order.setStatus("WAIT_PAY");
        order.setPayExpireAt(cmd.getPayExpireAt());
        order.setPaidThenCancelled(false);
        order.setItemSnapshot(writeSnapshot(cmd.getItemSnapshot()));
        order.setVersion(0);
        orderMapper.insert(order);
        return toView(order);
    }

    @Override
    @Transactional
    public void markPaid(Long orderId, Long paidAmount, String paymentMethod) {
        OrderEntity order = orderMapper.selectById(orderId);
        if (order == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        if (!"WAIT_PAY".equals(order.getStatus())) {
            if ("PAID".equals(order.getStatus())) {
                return; // 事件重放：幂等静默
            }
            if ("CANCELLED".equals(order.getStatus())) {
                // 超时抢先（ADR-006-4）：已支付单被超时取消属病态窗口，标记待人工退款，不抛错免渠道风暴
                log.error("订单已取消但收到支付成功 orderId={}，转人工退款", orderId);
                OrderEntity flag = new OrderEntity();
                flag.setId(orderId);
                flag.setPaidThenCancelled(true);
                orderMapper.updateById(flag);
                return;
            }
            throw new BusinessException(OrderErrorCode.NOT_PAYABLE);
        }
        if (paidAmount == null || !paidAmount.equals(order.getAmount())) {
            throw new BusinessException(OrderErrorCode.AMOUNT_MISMATCH);
        }
        if (orderMapper.updateMarkPaid(orderId, paidAmount, paymentMethod) == 0) {
            return; // 并发竞争失败方：对方已推进，幂等静默
        }
        events.publishEvent(new OrderPaidEvent(orderId, order.getAppointmentId(),
                order.getUserId(), LocalDateTime.now()));
    }

    @Override
    @Transactional
    public boolean cancelTimeout(Long orderId) {
        OrderEntity order = orderMapper.selectById(orderId);
        if (order == null) {
            return false;
        }
        if (orderMapper.updateCancelTimeout(orderId) == 0) {
            return false; // 回调已抢先 PAID：跳过（先到先得），不发取消事件
        }
        // 仅在 CAS 真正生效后发布；收件人 userId 取权威订单行，reason=TIMEOUT 区分触发源（文案用）
        events.publishEvent(new OrderTimeoutCancelledEvent(orderId, order.getAppointmentId(),
                order.getUserId(), OrderTimeoutCancelledEvent.REASON_TIMEOUT, LocalDateTime.now()));
        return true;
    }

    @Override
    @Transactional
    public boolean cancelUnpaid(Long orderId) {
        // 同一 WAIT_PAY→CANCELLED 条件更新（updateCancelTimeout）。CAS 落败=状态漂移，调用方回滚，不发事件。
        if (orderMapper.updateCancelTimeout(orderId) == 0) {
            return false;
        }
        // 顾客主动取消未支付单：取消真正生效后才发布，reason=USER。收件人取权威订单行（不让监听器回读）。
        // 与超时取消复用同一事件（业务结果相同：未付→已取消、无退款），触发源由 reason 区分。
        OrderEntity order = orderMapper.selectById(orderId);
        if (order != null) {
            events.publishEvent(new OrderTimeoutCancelledEvent(orderId, order.getAppointmentId(),
                    order.getUserId(), OrderTimeoutCancelledEvent.REASON_USER, LocalDateTime.now()));
        }
        return true;
    }

    @Override
    @Transactional
    public void refundOnCancel(Long orderId, long refundAmountFen, String reason) {
        OrderEntity order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(OrderErrorCode.ORDER_STATE_CONFLICT);
        }
        if (orderMapper.updateRefundOnCancel(orderId) == 0) {
            throw new BusinessException(OrderErrorCode.ORDER_STATE_CONFLICT);
        }
        long already = refundMapper.sumActiveRefunds(orderId);
        if (already + refundAmountFen > order.getPaidAmount()) {
            throw new BusinessException(OrderErrorCode.REFUND_EXCEEDED); // 第六阶段 §7 :125
        }
        RefundEntity refund = new RefundEntity();
        refund.setRefundNo("RF" + LocalDateTime.now().format(NO_DATE) + IdWorker.getId());
        refund.setOrderId(orderId);
        refund.setAmount(refundAmountFen);
        refund.setReason(reason);
        refund.setStatus("APPLYING"); // 渠道执行/审批随退款专段（ADR-023 §4）
        refund.setApplyBy("USER");
        refund.setManualOverride(false);
        refund.setVersion(0);
        refundMapper.insert(refund);
        // 通知门店"有退款待处理"由 app 层监听器消费（order→employee 非矩阵边，ADR-037 §5）
        events.publishEvent(new RefundAppliedEvent(refund.getId(), orderId, order.getAppointmentId(),
                order.getStoreId(), order.getUserId(), refundAmountFen, LocalDateTime.now()));
    }

    // ---------- 查询 ----------

    @Override
    public OrderView get(Long orderId) {
        OrderEntity order = orderId == null ? null : orderMapper.selectById(orderId);
        return order == null ? null : toView(order);
    }

    @Override
    public OrderView byAppointment(Long appointmentId) {
        if (appointmentId == null) {
            return null;
        }
        OrderEntity order = orderMapper.selectOne(Wrappers.<OrderEntity>lambdaQuery()
                .eq(OrderEntity::getAppointmentId, appointmentId).last("LIMIT 1"));
        return order == null ? null : toView(order);
    }

    @Override
    public PageResult<OrderView> pageForUser(Long userId, String status, int pageNum, int pageSize) {
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), 100);
        var mpPage = orderMapper.selectPage(
                com.baomidou.mybatisplus.extension.plugins.pagination.Page.of(page, size),
                Wrappers.<OrderEntity>lambdaQuery()
                        .eq(OrderEntity::getUserId, userId)
                        .eq(status != null && !status.isBlank(), OrderEntity::getStatus, status)
                        .orderByDesc(OrderEntity::getCreatedAt)
                        .orderByDesc(OrderEntity::getId));
        List<OrderView> list = mpPage.getRecords().stream().map(this::toView).toList();
        return PageResult.of(mpPage.getTotal(), page, size, list);
    }

    @Override
    public long countUnpaid(Long userId) {
        return userId == null ? 0 : orderMapper.countUnpaid(userId);
    }

    private String writeSnapshot(Map<String, Object> snapshot) {
        try {
            return json.writeValueAsString(snapshot == null ? Map.of() : snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("item_snapshot 序列化失败", e);
        }
    }

    private OrderView toView(OrderEntity o) {
        return OrderView.builder()
                .id(o.getId()).orderNo(o.getOrderNo()).appointmentId(o.getAppointmentId())
                .userId(o.getUserId()).storeId(o.getStoreId()).amount(o.getAmount())
                .status(o.getStatus()).payExpireAt(o.getPayExpireAt()).paidAt(o.getPaidAt())
                .paymentMethod(o.getPaymentMethod()).itemSnapshot(readSnapshot(o.getItemSnapshot()))
                .createdAt(o.getCreatedAt())
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
}
