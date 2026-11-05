package com.petplatform.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.app.security.JwtAuthFilter;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全装配（ADR-008-1）：无状态 JWT + 方法级 @PreAuthorize 权限码校验。
 * 认证/鉴权失败统一返回 HTTP 200 + Result 非零 code（与全局响应约定一致）。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PERMIT_ALL = {
            "/api/v1/public/**",
            "/api/v1/auth/**",
            "/api/v1/payment/callback/**",
            "/doc.html",
            "/webjars/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-resources/**",
            "/favicon.ico",
            "/actuator/health"
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter, ObjectMapper objectMapper)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        // ADR-011：改密需登录态，须排在 /api/v1/auth/** permitAll 之前
                        .requestMatchers("/api/v1/auth/password/change").authenticated()
                        // ADR-012：B 端员工身份查询仅需登录态（storeCtx 引导端点，见 EmployeeIdentityController）
                        .requestMatchers("/api/v1/auth/employee-identities").authenticated()
                        .requestMatchers(PERMIT_ALL).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                writeResult(res, objectMapper, CommonErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, e) ->
                                writeResult(res, objectMapper, CommonErrorCode.FORBIDDEN)))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeResult(HttpServletResponse res, ObjectMapper mapper, CommonErrorCode code) throws java.io.IOException {
        res.setStatus(HttpServletResponse.SC_OK);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        Result<Void> body = Result.fail(code);
        body.setTraceId(org.slf4j.MDC.get(com.petplatform.app.web.TraceIdFilter.MDC_KEY));
        mapper.writeValue(res.getWriter(), body);
    }
}
