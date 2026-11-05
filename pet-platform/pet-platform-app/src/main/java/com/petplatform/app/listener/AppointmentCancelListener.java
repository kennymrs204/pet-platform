package com.petplatform.app.listener;

import com.petplatform.appointment.api.event.AppointmentCancelledEvent;
import com.petplatform.boarding.api.BoardingStayProvisioningApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 预约取消事件桥（ADR-023 §3，PaymentChainListener 同构先例）：
 * appointment→boarding 非矩阵边，消费者只能落装配根；同步 @EventListener 加入取消事务——
 * "预约 CANCELLED + 占用释放 + 订单退款态 + EXPECTED 住单撤销"同一本地事务原子。
 * 仅 MULTI_DAY 触发；EXPECTED 逻辑删幂等，重放安全。
 */
@Slf4j
@Component
public class AppointmentCancelListener {

    private final BoardingStayProvisioningApi boardingStayProvisioningApi;

    public AppointmentCancelListener(BoardingStayProvisioningApi boardingStayProvisioningApi) {
        this.boardingStayProvisioningApi = boardingStayProvisioningApi;
    }

    @EventListener
    public void onAppointmentCancelled(AppointmentCancelledEvent event) {
        if (event.multiDay()) {
            boardingStayProvisioningApi.cancelExpected(event.appointmentId());
        }
    }
}
