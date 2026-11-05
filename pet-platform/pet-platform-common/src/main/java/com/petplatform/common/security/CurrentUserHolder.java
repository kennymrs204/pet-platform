package com.petplatform.common.security;

import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.error.BusinessException;

/**
 * 当前登录人持有器。app 的 JwtAuthFilter 写入，业务 Service 读取以实现
 * SELF/TASK 数据范围的显式 WHERE 条件（第八阶段 §1.3：不用拦截器隐式魔法）。
 */
public final class CurrentUserHolder {

    private static final ThreadLocal<AuthPrincipal> HOLDER = new ThreadLocal<>();

    private CurrentUserHolder() {
    }

    public static void set(AuthPrincipal principal) {
        HOLDER.set(principal);
    }

    public static AuthPrincipal get() {
        return HOLDER.get();
    }

    public static AuthPrincipal require() {
        AuthPrincipal p = HOLDER.get();
        if (p == null) {
            throw BusinessException.of(CommonErrorCode.UNAUTHORIZED);
        }
        return p;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
