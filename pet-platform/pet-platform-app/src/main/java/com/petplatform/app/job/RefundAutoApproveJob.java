package com.petplatform.app.job;

import com.petplatform.app.service.PlatformParamQuery;
import com.petplatform.common.time.BusinessDates;
import com.petplatform.order.api.RefundApprovalApi;
import com.petplatform.order.api.RefundQueryApi;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 待处理退款超时自动通过（本单设计：规定时间内门店不拒绝 → 自动走退款路线）。
 * 时限来自 sys_config {@code refund.auto_approve_minutes}（平台参数，≤0=关闭本任务）；
 * 多实例互斥/逐单隔离与 {@link OrderTimeoutJob} 同款配方。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pet.job", name = "refund-auto-approve", havingValue = "enabled", matchIfMissing = true)
public class RefundAutoApproveJob {

    private static final String MINUTES_KEY = "refund.auto_approve_minutes";
    private static final long DEFAULT_MINUTES = 20L;

    private final RefundQueryApi refundQueryApi;
    private final RefundApprovalApi refundApprovalApi;
    private final PlatformParamQuery platformParamQuery;
    private final RedissonClient redisson;

    public RefundAutoApproveJob(RefundQueryApi refundQueryApi, RefundApprovalApi refundApprovalApi,
                                PlatformParamQuery platformParamQuery, RedissonClient redisson) {
        this.refundQueryApi = refundQueryApi;
        this.refundApprovalApi = refundApprovalApi;
        this.platformParamQuery = platformParamQuery;
        this.redisson = redisson;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void scan() {
        long minutes = platformParamQuery.longValue(MINUTES_KEY, DEFAULT_MINUTES);
        if (minutes <= 0) {
            return; // 平台参数关闭开关
        }
        var lock = redisson.getLock("lock:job:refund-auto-approve");
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
            LocalDateTime cutoff = LocalDateTime.now(BusinessDates.ZONE).minusMinutes(minutes);
            for (Long refundId : refundQueryApi.autoApprovableIds(cutoff)) {
                try {
                    refundApprovalApi.autoApprove(refundId);
                    log.info("待处理退款超时自动通过 refundId={} cutoff={}", refundId, cutoff);
                } catch (Exception e) {
                    // 单笔失败不影响其余候选，下个周期重试
                    log.warn("待处理退款自动通过跳过 refundId={}: {}", refundId, e.getMessage());
                }
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
