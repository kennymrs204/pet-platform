package com.petplatform.payment.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.payment.api.PaymentCommandApi;
import com.petplatform.payment.api.PaymentQueryApi;
import com.petplatform.payment.api.dto.OfflinePaymentView;
import com.petplatform.payment.api.dto.PrepayView;
import com.petplatform.payment.api.event.PaymentSucceededEvent;
import com.petplatform.payment.domain.PaymentEntity;
import com.petplatform.payment.gateway.PaymentGateway;
import com.petplatform.payment.mapper.PaymentMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 支付流水与回调编排（表 Owner=payment，ADR-016 §8）。
 * 通道由 pet.payment.channel 显式选择（mock|wechat）；未配置=启动失败（fail-fast，防生产裸奔）。
 * 回调链在发布方事务内同步推进订单（app 桥接 listener），任一环节异常整体回滚由渠道重试兜底。
 */
@Slf4j
@Service
public class PaymentService implements PaymentCommandApi, PaymentQueryApi {

    private static final String CHANNEL_OFFLINE = "OFFLINE";
    private static final int NOTE_MAX = 200;

    private final PaymentMapper paymentMapper;
    private final ApplicationEventPublisher events;
    private final List<PaymentGateway> gateways;

    @Value("${pet.payment.channel:}")
    private String configuredChannel;

    public PaymentService(PaymentMapper paymentMapper,
                          ApplicationEventPublisher events, List<PaymentGateway> gateways) {
        this.paymentMapper = paymentMapper;
        this.events = events;
        this.gateways = gateways;
    }

    @PostConstruct
    void failFastWithoutChannel() {
        if (configuredChannel.isBlank() || gateways.isEmpty()) {
            throw new IllegalStateException(
                    "pet.payment.channel 未配置或无匹配 PaymentGateway（mock|wechat），启动失败（ADR-016 §8）");
        }
    }

    private PaymentGateway gateway(String channel) {
        String want = (channel == null || channel.isBlank() ? configuredChannel : channel)
                .toUpperCase(Locale.ROOT);
        return gateways.stream().filter(g -> g.channel().equalsIgnoreCase(want)).findFirst()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.PARAM_INVALID, "未知支付通道: " + channel));
    }

    @Override
    @Transactional
    public PrepayView createPrepay(Long orderId, Long amount, String subject) {
        if (orderId == null || amount == null || amount <= 0) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "支付参数非法");
        }
        PaymentEntity txn = paymentMapper.selectProcessingByOrder(orderId);
        if (txn == null) {
            txn = new PaymentEntity();
            txn.setPaymentNo("PN" + IdWorker.getId());
            txn.setOrderId(orderId);
            txn.setChannel(gateway(null).channel());
            txn.setAmount(amount);
            txn.setStatus("PROCESSING");
            txn.setAmountMismatch(false);
            txn.setVersion(0);
            paymentMapper.insert(txn);
        }
        Map<String, String> params = gateway(null).prepay(txn.getPaymentNo(), amount, subject);
        return PrepayView.builder().paymentNo(txn.getPaymentNo()).params(params).build();
    }

    @Override
    @Transactional
    public String handleCallback(String channel, String rawBody) {
        PaymentGateway gw = gateway(channel);
        String[] parsed;
        try {
            parsed = gw.verifyAndParse(rawBody);
        } catch (Exception e) {
            log.warn("支付回调验签/解析失败 channel={}: {}", gw.channel(), e.getMessage());
            return gw.ackFail("verify_failed");
        }
        String paymentNo = parsed[0];
        String txnNo = parsed[1];
        if (paymentMapper.countByChannelTxn(txnNo) > 0) {
            return gw.ackSuccess(); // 防线1：渠道重复投递
        }
        PaymentEntity txn = paymentMapper.selectByPaymentNo(paymentNo);
        if (txn == null) {
            return gw.ackFail("unknown_payment_no");
        }
        String paidAmount = parsed.length > 2 ? parsed[2] : null;
        if (paidAmount != null && !paidAmount.equals(String.valueOf(txn.getAmount()))) {
            // ADR-006-3：金额不符只告警不推进（应答成功止重试），人工核对
            log.error("支付金额不符 paymentNo={} 流水={} 渠道={}", paymentNo, txn.getAmount(), paidAmount);
            PaymentEntity mark = new PaymentEntity();
            mark.setId(txn.getId());
            mark.setAmountMismatch(true);
            paymentMapper.updateById(mark);
            return gw.ackSuccess();
        }
        if (paymentMapper.updateSuccess(txn.getId(), txnNo, rawBody) == 0) {
            return gw.ackSuccess(); // 并发/重放：已 SUCCESS
        }
        events.publishEvent(new PaymentSucceededEvent(txn.getOrderId(), txn.getAmount(),
                gw.channel(), txnNo, LocalDateTime.now()));
        return gw.ackSuccess();
    }

    @Override
    @Transactional
    public String queryFinalStatus(Long orderId) {
        String status = orderId == null ? null : paymentMapper.selectLatestStatus(orderId);
        if (status == null) {
            return "NONE";
        }
        if (!"PROCESSING".equals(status)) {
            return status;
        }
        PaymentEntity txn = paymentMapper.selectProcessingByOrder(orderId);
        if (txn == null) {
            return status;
        }
        if (CHANNEL_OFFLINE.equals(txn.getChannel())) {
            // 线下收款无渠道可查（ADR-006 §6"超时取消跳过渠道查询"）：登记未复核即 PROCESSING，
            // 直接回当前状态——绝不能掉进 gateway()（无 OFFLINE 网关必抛异常，会把"宁可晚取消"变成"永不取消"）
            return txn.getStatus();
        }
        String channelStatus = gateway(txn.getChannel()).queryStatus(txn.getPaymentNo());
        if ("SUCCESS".equals(channelStatus)
                && paymentMapper.updateSuccess(txn.getId(), "QUERY-" + txn.getPaymentNo(), "主动查单") > 0) {
            // 防线3命中：就地补偿推进（与回调链同一事件路径，幂等）
            log.warn("主动查单发现已支付并补偿推进 orderId={}（回调丢失场景）", orderId);
            events.publishEvent(new PaymentSucceededEvent(orderId, txn.getAmount(),
                    txn.getChannel(), "QUERY-" + txn.getPaymentNo(), LocalDateTime.now()));
        }
        return channelStatus;
    }

    @Override
    @Transactional
    public OfflinePaymentView registerOffline(Long orderId, Long amount, Long registrantUserId, String note) {
        if (orderId == null || registrantUserId == null || amount == null || amount <= 0) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "线下收款登记参数非法");
        }
        if (note != null && note.length() > NOTE_MAX) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "登记备注不能超过" + NOTE_MAX + "字");
        }
        PaymentEntity latest = paymentMapper.selectLatestOfflineByOrder(orderId);
        if (latest != null && "PROCESSING".equals(latest.getStatus())) {
            return toOfflineView(latest); // 幂等复用：重复登记回到同一笔待复核流水
        }
        if (latest != null && "SUCCESS".equals(latest.getStatus())) {
            throw new BusinessException(CommonErrorCode.ILLEGAL_STATE_TRANSITION, "该订单线下收款已复核，无需再次登记");
        }
        PaymentEntity txn = new PaymentEntity();
        txn.setPaymentNo("PN" + IdWorker.getId());
        txn.setOrderId(orderId);
        txn.setChannel(CHANNEL_OFFLINE);
        txn.setAmount(amount);
        txn.setStatus("PROCESSING");
        txn.setAmountMismatch(false);
        txn.setRegisteredBy(registrantUserId);
        txn.setRegisteredNote(note);
        txn.setVersion(0);
        paymentMapper.insert(txn);
        return toOfflineView(txn);
    }

    @Override
    @Transactional
    public OfflinePaymentView confirmOffline(Long orderId, Long confirmerUserId) {
        if (orderId == null || confirmerUserId == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "线下收款复核参数非法");
        }
        PaymentEntity latest = paymentMapper.selectLatestOfflineByOrder(orderId);
        if (latest == null) {
            throw new BusinessException(CommonErrorCode.ILLEGAL_STATE_TRANSITION, "该订单尚未登记线下收款");
        }
        if (!"PROCESSING".equals(latest.getStatus())) {
            throw new BusinessException(CommonErrorCode.ILLEGAL_STATE_TRANSITION, "该笔线下收款已复核或已关闭");
        }
        if (confirmerUserId.equals(latest.getRegisteredBy())) {
            // 双人原则硬闸：判据是登记时落库的 registered_by，不信任前端传参
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "复核人与登记人不得为同一人");
        }
        String txnNo = "OFFLINE-" + latest.getPaymentNo();
        if (paymentMapper.updateOfflineSuccess(latest.getId(), txnNo, confirmerUserId) == 0) {
            throw new BusinessException(CommonErrorCode.ILLEGAL_STATE_TRANSITION, "该笔线下收款已被他人复核");
        }
        // 与回调链同一事件路径：桥接监听器（同步同事务）推进 订单 PAID → 预约 CONFIRMED → 入住单 EXPECTED
        events.publishEvent(new PaymentSucceededEvent(orderId, latest.getAmount(),
                CHANNEL_OFFLINE, txnNo, LocalDateTime.now()));
        return toOfflineView(paymentMapper.selectByPaymentNo(latest.getPaymentNo()));
    }

    @Override
    public OfflinePaymentView latestOfflineByOrder(Long orderId) {
        PaymentEntity txn = orderId == null ? null : paymentMapper.selectLatestOfflineByOrder(orderId);
        return txn == null ? null : toOfflineView(txn);
    }

    private OfflinePaymentView toOfflineView(PaymentEntity t) {
        return OfflinePaymentView.builder()
                .id(t.getId()).paymentNo(t.getPaymentNo()).orderId(t.getOrderId())
                .amount(t.getAmount()).status(t.getStatus())
                .registeredBy(t.getRegisteredBy()).registeredNote(t.getRegisteredNote())
                .confirmedBy(t.getConfirmedBy()).createdAt(t.getCreatedAt()).paidAt(t.getPaidAt())
                .build();
    }
}
