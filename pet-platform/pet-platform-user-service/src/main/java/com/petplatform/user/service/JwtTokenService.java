package com.petplatform.user.service;

import com.petplatform.user.api.AuthTokenApi;
import com.petplatform.user.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT 实现（jjwt 0.13.0，HS256 起步，ADR-008-1）。
 * 令牌只装身份：uid/eid/jti/typ，权限码一律不进 token。
 */
@Service
public class JwtTokenService implements AuthTokenApi {

    private static final String CLAIM_UID = "uid";
    private static final String CLAIM_EID = "eid";
    private static final String CLAIM_TYP = "typ";

    private final JwtProperties props;
    private final SecretKey key;

    public JwtTokenService(JwtProperties props) {
        this.props = props;
        byte[] secretBytes = (props.secret() == null ? "" : props.secret()).getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "pet.jwt.secret 至少 32 字节（HS256）。请通过环境变量 PET_JWT_SECRET 注入。");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    @Override
    public IssuedTokens issueTokens(Long userId, Long employeeId) {
        String access = build(userId, employeeId, TokenType.ACCESS, props.accessExpireSeconds());
        String refresh = build(userId, employeeId, TokenType.REFRESH, props.refreshExpireSeconds());
        return new IssuedTokens(access, refresh, props.accessExpireSeconds());
    }

    @Override
    public Optional<ParsedToken> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(props.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long userId = claims.get(CLAIM_UID, Number.class).longValue();
            Number eid = claims.get(CLAIM_EID, Number.class);
            Long employeeId = eid == null ? 0L : eid.longValue();
            TokenType type = TokenType.valueOf(claims.get(CLAIM_TYP, String.class));
            long exp = claims.getExpiration().toInstant().getEpochSecond();
            return Optional.of(new ParsedToken(userId, employeeId, claims.getId(), type, exp));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String build(Long userId, Long employeeId, TokenType type, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(props.issuer())
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_UID, userId)
                .claim(CLAIM_EID, employeeId == null ? 0L : employeeId)
                .claim(CLAIM_TYP, type.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }
}
