package com.petplatform.appointment.api.event;

import java.time.LocalDateTime;

/**
 * 预约完成事件（ADR-053）：预约落到终态 COMPLETED 的两个唯一入口都会发布——
 * {@code finishService}（INSTANT 履约完成）与 {@code completeBoarding}（托管离店联动）。
 * 订阅方（顾客"服务已完成"站内信）须 @TransactionalEventListener(AFTER_COMMIT)。
 *
 * <p>为什么不发两个事件：顾客视角只有一句"你的单做完了"，按来源分文案即可，
 * 不必为收件人不变的同一事实开两个类型。{@code source} 供文案分支：
 * {@code INSTANT_FINISH} → "宠物可以接走了"；{@code BOARDING_CHECKOUT} → "已办理离店"。
 *
 * <p>发布点都在 CAS 成功之后、同一事务内——AFTER_COMMIT 保证"预约回滚了消息却留下"不会发生；
 * CAS==0（幂等重放/状态已推进）不发布，重复完成不会堆消息（推送侧另有 pushOnce 双保险）。
 *
 * <p>{@code petId/actualStartAt/actualEndAt} 都取自完成那一手的权威预约实体，不是计划时间：
 * 通知侧用它们补宠物名和真实履约跨度；缺失时通知降级为原基础文案。
 */
public record AppointmentCompletedEvent(
        Long appointmentId,
        Long userId,
        Long storeId,
        Long petId,
        String serviceName,
        String source,
        LocalDateTime actualStartAt,
        LocalDateTime actualEndAt,
        LocalDateTime occurredAt
) {
    public static final String SOURCE_INSTANT_FINISH = "INSTANT_FINISH";
    public static final String SOURCE_BOARDING_CHECKOUT = "BOARDING_CHECKOUT";
}
