package com.petplatform.app.job;

import com.petplatform.appointment.api.AppointmentCommandApi;
import com.petplatform.appointment.api.dto.TimeoutAppointmentView;
import com.petplatform.order.api.OrderCommandApi;
import com.petplatform.payment.api.PaymentCommandApi;
import com.petplatform.schedule.api.ResourceOccupancyApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 超时取消单条事务（ADR-016 §4）：订单取消 + 预约取消 + 资源释放同一本地事务，
 * 任一步失败整体回滚——不存在"单已取消、预约还活着、笼位已释放"的半截态。
 * 独立 bean 保证 @Transactional 代理生效（Job 循环调用）。
 */
@Service
public class TimeoutCancelTx {

    private final PaymentCommandApi paymentCommandApi;
    private final OrderCommandApi orderCommandApi;
    private final AppointmentCommandApi appointmentCommandApi;
    private final ResourceOccupancyApi occupancyApi;

    public TimeoutCancelTx(PaymentCommandApi paymentCommandApi, OrderCommandApi orderCommandApi,
                           AppointmentCommandApi appointmentCommandApi, ResourceOccupancyApi occupancyApi) {
        this.paymentCommandApi = paymentCommandApi;
        this.orderCommandApi = orderCommandApi;
        this.appointmentCommandApi = appointmentCommandApi;
        this.occupancyApi = occupancyApi;
    }

    /** @return true=本次取消生效 */
    @Transactional
    public boolean cancelOne(TimeoutAppointmentView view) {
        // 竞态前置：先查支付终态（SUCCESS 会就地补偿推进——回调丢失防线3）
        String status = paymentCommandApi.queryFinalStatus(view.getOrderId());
        if ("SUCCESS".equals(status)) {
            return false;
        }
        if (!orderCommandApi.cancelTimeout(view.getOrderId())) {
            return false; // 回调已抢先 PAID：先到先得，跳过
        }
        appointmentCommandApi.cancelTimeout(view.getAppointmentId()); // 0 行抛 50003 → 整体回滚
        occupancyApi.releaseAll(view.getAppointmentId());
        return true;
    }
}
