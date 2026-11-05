package com.petplatform.pet.api.event;

import java.time.LocalDateTime;

/**
 * 宠物建档事件（第二阶段 §事件清单：pet → notification 欢迎/引导，后续段接入）。
 * 事务内发布；订阅方须用 @TransactionalEventListener(AFTER_COMMIT)。当前无订阅方，保留扩展点。
 */
public record PetCreatedEvent(
        Long petId,
        Long ownerId,
        LocalDateTime occurredAt
) {
}
