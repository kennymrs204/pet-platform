package com.petplatform.app.listener;

import com.petplatform.appointment.api.AppointmentCommandApi;
import com.petplatform.appointment.api.event.AppointmentPaidEvent;
import com.petplatform.boarding.api.BoardingStayProvisioningApi;
import com.petplatform.order.api.OrderCommandApi;
import com.petplatform.order.api.event.OrderPaidEvent;
import com.petplatform.payment.api.event.PaymentSucceededEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 支付成功事件桥（ADR-016 §2）：消费者统一住 app 装配层（ArchUnit 规则 4/5 禁实现包反向依赖，
 * PermissionAssembler 先例），同步 @EventListener 加入发布方事务——
 * "流水 SUCCESS → 订单 PAID → 预约 CONFIRMED →（MULTI_DAY）入住单 EXPECTED"同一本地事务原子推进
 * （ADR-006-2 回调链裁决）；任一环节异常整体回滚，渠道按重试语义再投，全链幂等。
 */
@Slf4j
@Component
public class PaymentChainListener {

    private final OrderCommandApi orderCommandApi;
    private final AppointmentCommandApi appointmentCommandApi;
    private final BoardingStayProvisioningApi boardingStayProvisioningApi;

    public PaymentChainListener(OrderCommandApi orderCommandApi, AppointmentCommandApi appointmentCommandApi,
                                BoardingStayProvisioningApi boardingStayProvisioningApi) {
        this.orderCommandApi = orderCommandApi;
        this.appointmentCommandApi = appointmentCommandApi;
        this.boardingStayProvisioningApi = boardingStayProvisioningApi;
    }

    @EventListener
    public void onPaymentSucceeded(PaymentSucceededEvent event) {
        // 渠道→支付方式展示口径归一：线下收款显式 OFFLINE（B 端显示"线下登记"）；其余（含 MOCK/WECHAT）均为线上
        String paymentMethod = "OFFLINE".equals(event.channel()) ? "OFFLINE" : "ONLINE";
        orderCommandApi.markPaid(event.orderId(), event.amount(), paymentMethod);
    }

    @EventListener
    public void onOrderPaid(OrderPaidEvent event) {
        appointmentCommandApi.confirm(event.appointmentId());
    }

    @EventListener
    public void onAppointmentPaid(AppointmentPaidEvent event) {
        if (event.multiDay()) {
            boardingStayProvisioningApi.ensureExpected(event.appointmentId());
        }
    }
}
