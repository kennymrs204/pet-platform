package com.petplatform.user.api;

/**
 * 令牌端口：签发/解析 JWT（第二阶段：JWT 签发与刷新归 user 模块；
 * app 的 Security 装配通过本接口消费，不触碰 jjwt 类型，保持 api 纯净）。
 */
public interface AuthTokenApi {

    enum TokenType { ACCESS, REFRESH }

    record ParsedToken(
            Long userId,
            Long employeeId,
            String jti,
            TokenType type,
            long expireAtEpochSecond
    ) {
    }

    record IssuedTokens(
            String accessToken,
            String refreshToken,
            long accessExpiresIn
    ) {
    }

    /** 签发 access(2h)+refresh(14d) 双 token（ADR-008-2）。employeeId 可为 null（纯顾客）。 */
    IssuedTokens issueTokens(Long userId, Long employeeId);

    /** 解析并校验签名/过期；非法或过期返回 empty。 */
    java.util.Optional<ParsedToken> parse(String token);
}
