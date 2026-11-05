package com.petplatform.app.job;

import com.petplatform.appointment.api.AppointmentCommandApi;
import com.petplatform.appointment.api.AppointmentQueryApi;
import com.petplatform.appointment.api.dto.TimeoutAppointmentView;
import com.petplatform.common.time.BusinessDates;
import com.petplatform.order.api.OrderCommandApi;
import com.petplatform.payment.api.PaymentCommandApi;
import com.petplatform.schedule.api.ResourceOccupancyApi;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 未支付超时取消闭环（ADR-016 §4 / 第六阶段 §超时链）：
 * 多实例互斥 = Redisson tryLock(0,55s)（DistributedLockTemplate 同款语义，此处内联避免包依赖）；
 * 每单独立事务（TimeoutCancelTx bean 代理生效）；
 * 竞态裁决：先查支付终态（含主动查单补偿），再条件更新先到先得；查询异常=宁可晚取消跳过本单。
 */
@Slf4j
@Component
// 只读实例（本机直跑接共享库做验收）用 --pet.job.order-timeout=disabled 关掉本任务：
// 第二实例一旦抢到 Redisson 锁就会替主实例执行取消/退款这类业务写。
// matchIfMissing=true ⇒ 现有部署（不配这个键）行为完全不变。
@ConditionalOnProperty(prefix = "pet.job", name = "order-timeout", havingValue = "enabled", matchIfMissing = true)
public class OrderTimeoutJob {

    private final AppointmentQueryApi appointmentQueryApi;
    private final TimeoutCancelTx cancelTx;
    private final RedissonClient redisson;

    public OrderTimeoutJob(AppointmentQueryApi appointmentQueryApi, TimeoutCancelTx cancelTx,
                           RedissonClient redisson) {
        this.appointmentQueryApi = appointmentQueryApi;
        this.cancelTx = cancelTx;
        this.redisson = redisson;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void scan() {
        var lock = redisson.getLock("lock:job:order-timeout");
        boolean acquired;
        try {
            acquired = lock.tryLock(0, 55, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!acquired) {
            return; // 其它实例在跑
        }
        try {
            List<TimeoutAppointmentView> due =
                    appointmentQueryApi.scanTimeoutAppointments(LocalDateTime.now(BusinessDates.ZONE), 50);
            for (TimeoutAppointmentView v : due) {
                try {
                    boolean cancelled = cancelTx.cancelOne(v);
                    if (cancelled) {
                        log.info("超时取消完成 appointmentId={} orderId={}", v.getAppointmentId(), v.getOrderId());
                    }
                } catch (Exception e) {
                    // 含支付通道查询失败：宁可晚取消不可错取消，下个周期重试
                    log.warn("超时取消单条跳过 appointmentId={}: {}", v.getAppointmentId(), e.getMessage());
                }
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
