package com.petplatform.app.security;

import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.user.api.AuthTokenApi;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * JWT 认证过滤器（ADR-008-1/2）。
 * 解析走 user-api 的 AuthTokenApi（app 不触碰 jjwt）；只认 ACCESS 型令牌；
 * 登出黑名单 blacklist:{jti} 命中即视为未认证；
 * 权限码 Redis 缓存 miss 时由 PermissionAssembler 现场重建（§1.2）。
 * Authority = "perm:" + 能力编码（§1.2：hasAuthority('perm:appointment:assign')）。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    public static final String HEADER_STORE = "X-Store-Id";
    private static final String AUTHORITY_PREFIX = "perm:";
    private static final String KEY_BLACKLIST = "blacklist:%s";

    private final AuthTokenApi tokenApi;
    private final PermissionAssembler permissionAssembler;
    private final StringRedisTemplate redis;

    public JwtAuthFilter(AuthTokenApi tokenApi, PermissionAssembler permissionAssembler, StringRedisTemplate redis) {
        this.tokenApi = tokenApi;
        this.permissionAssembler = permissionAssembler;
        this.redis = redis;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            authenticate(request);
            chain.doFilter(request, response);
        } finally {
            CurrentUserHolder.clear();
        }
    }

    private void authenticate(HttpServletRequest request) {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            return;
        }
        String token = resolveToken(request);
        if (token == null) {
            return;
        }
        Optional<AuthTokenApi.ParsedToken> parsedOpt = tokenApi.parse(token);
        if (parsedOpt.isEmpty()) {
            return;
        }
        AuthTokenApi.ParsedToken parsed = parsedOpt.get();
        if (parsed.type() != AuthTokenApi.TokenType.ACCESS) {
            return;
        }
        if (Boolean.TRUE.equals(redis.hasKey(String.format(KEY_BLACKLIST, parsed.jti())))) {
            return;
        }
        Long storeCtx = resolveStoreCtx(request);
        long ttl = parsed.expireAtEpochSecond() - Instant.now().getEpochSecond();
        // employeeId 在请求装配期按 user×store 上下文解析（token 的 eid 仅签发期占位）：
        // user 模块按 §3.2 矩阵不查 emp_*，权威来源是 EmployeeQueryApi，由 app 装配点统一解析。
        PermissionAssembler.Assembled assembled = permissionAssembler.resolve(parsed.userId(), storeCtx, ttl);
        if (assembled == null) {
            return;   // 账号不存在/已封禁：按未认证处理，由 entry point 回 10002
        }
        Set<String> codes = assembled.codes();
        AuthPrincipal principal = new AuthPrincipal(
                parsed.userId(), assembled.employeeId(), storeCtx,
                parsed.jti(), parsed.expireAtEpochSecond(), Set.copyOf(codes));
        var authorities = codes.stream()
                .map(code -> new SimpleGrantedAuthority(AUTHORITY_PREFIX + code))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
        CurrentUserHolder.set(principal);
        if (log.isDebugEnabled()) {
            log.debug("[auth] uid={} ctx={} eid={} codes={}",
                    parsed.userId(), storeCtx, assembled.employeeId(), codes.size());
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String t = header.substring(7).trim();
            return t.isEmpty() ? null : t;
        }
        return null;
    }

    private Long resolveStoreCtx(HttpServletRequest request) {
        String h = request.getHeader(HEADER_STORE);
        if (h == null || h.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(h.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
