package com.petplatform.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置。secret 必须经环境变量注入（ADR-006-7：密钥不落库不入 git），
 * 本地开发可用 application-local.yml（已被 .gitignore 忽略）。
 */
@ConfigurationProperties(prefix = "pet.jwt")
public record JwtProperties(
        String secret,
        long accessExpireSeconds,
        long refreshExpireSeconds,
        String issuer
) {
    public JwtProperties {
        if (accessExpireSeconds <= 0) {
            accessExpireSeconds = 7200;           // V 裁决：access 2h
        }
        if (refreshExpireSeconds <= 0) {
            refreshExpireSeconds = 1209600;       // V 裁决：refresh 14d
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "pet-platform";
        }
    }
}
