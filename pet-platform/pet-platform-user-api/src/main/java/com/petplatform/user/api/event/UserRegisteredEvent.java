package com.petplatform.user.api.event;

import java.time.LocalDateTime;

/**
 * 用户注册事件（第二阶段 §事件清单：user → notification）。
 * 脚手架期暂无监听方，随 notification 模块实现接入。
 */
public record UserRegisteredEvent(
        Long userId,
        String phone,
        LocalDateTime occurredAt
) {
}
