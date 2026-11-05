package com.petplatform.common.security;

import java.util.Set;

/**
 * 认证主体（纯 JDK 类型，放 common 供全部业务模块读取）。
 * storeCtx=0 表示 C 端/平台级上下文；>0 为 X-Store-Id 门店上下文。
 * jti/accessExpireAt 供登出黑名单计算剩余 TTL（ADR-008-2）。
 */
public record AuthPrincipal(
        Long userId,
        Long employeeId,
        Long storeCtx,
        String jti,
        long accessExpireAt,
        Set<String> permissions
) {
    public boolean isEmployee() {
        return employeeId != null && employeeId != 0L;
    }
}
