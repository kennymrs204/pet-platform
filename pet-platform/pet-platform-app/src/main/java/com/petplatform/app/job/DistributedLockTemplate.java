package com.petplatform.app.job;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁装饰器：为 @Scheduled 定时任务提供跨实例互斥（第六阶段对账/超时扫描任务）。
 * 采用 tryLock(0, leaseTime)：抢不到立即返回（其它实例在执行），执行完主动释放。
 * Redisson 看门狗在 leaseTime>0 时不续期，故 leaseTime 需覆盖任务最长耗时。
 */
@Component
public class DistributedLockTemplate {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockTemplate.class);

    private final RedissonClient redisson;

    public DistributedLockTemplate(RedissonClient redisson) {
        this.redisson = redisson;
    }

    /**
     * @param lockKey       锁键，建议 lock:job:{name}
     * @param leaseSeconds  自动释放时间（秒），需大于任务最长耗时
     * @param task          任务体
     * @return 是否真正执行（抢到锁）
     */
    public boolean runWithLock(String lockKey, long leaseSeconds, Runnable task) {
        RLock lock = redisson.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, leaseSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug("跳过任务，未获取到锁 {}", lockKey);
                return false;
            }
            task.run();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("获取锁被中断 {}", lockKey, e);
            return false;
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public <T> T callWithLock(String lockKey, long leaseSeconds, Supplier<T> task) {
        java.util.concurrent.atomic.AtomicReference<T> ref = new java.util.concurrent.atomic.AtomicReference<>();
        boolean ran = runWithLock(lockKey, leaseSeconds, () -> ref.set(task.get()));
        return ran ? ref.get() : null;
    }
}
