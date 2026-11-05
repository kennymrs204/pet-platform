package com.petplatform.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.petplatform.common.error.BusinessException;
import com.petplatform.user.api.AuthTokenApi;
import com.petplatform.user.api.UserErrorCode;
import com.petplatform.user.api.UserSessionApi;
import com.petplatform.user.api.dto.LoginRequest;
import com.petplatform.user.api.dto.PasswordChangeRequest;
import com.petplatform.user.api.dto.PasswordLoginRequest;
import com.petplatform.user.api.dto.PasswordResetRequest;
import com.petplatform.user.api.dto.RegisterRequest;
import com.petplatform.user.api.dto.TokenResponse;
import com.petplatform.user.api.event.UserRegisteredEvent;
import com.petplatform.user.domain.UserEntity;
import com.petplatform.user.mapper.UserMapper;
import com.petplatform.common.security.CurrentUserHolder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 注册/登录/刷新/登出（ADR-009：注册与登录分离）：
 * - register：验证码+密码建档（手机号已注册→20001）
 * - login：验证码登录，不再自动建号（未注册→20003）
 * - loginByPassword：BCrypt 校验（账号不存在/密码错统一 20002，防枚举）
 * - refresh 白名单 refresh:{userId}:{jti}，刷新即轮换
 * - 登出 = 删白名单 + access 入剩余 TTL 黑名单 blacklist:{jti}
 * 权限码装配不在此处（user 模块不依赖 employee，见 §3.2 矩阵），
 * 由 app 的 PermissionAssembler 在首个请求缓存 miss 时完成（§1.2“下次请求重建”）。
 */
@Service
public class AuthService implements UserSessionApi {

    private static final String KEY_REFRESH = "refresh:%d:%s";
    private static final String KEY_BLACKLIST = "blacklist:%s";

    private final UserMapper userMapper;
    private final SmsCodeService smsCodeService;
    private final AuthTokenApi tokenApi;
    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;
    private final CustomerProfileService customerProfileService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(UserMapper userMapper, SmsCodeService smsCodeService, AuthTokenApi tokenApi,
                       StringRedisTemplate redis, ApplicationEventPublisher events,
                       CustomerProfileService customerProfileService) {
        this.userMapper = userMapper;
        this.smsCodeService = smsCodeService;
        this.tokenApi = tokenApi;
        this.redis = redis;
        this.events = events;
        this.customerProfileService = customerProfileService;
    }

    /** 验证码注册（ADR-009）：建档即设密码，注册不自动登录。ADR-011：REGISTER 场景码。 */
    @Transactional
    public void register(RegisterRequest req) {
        smsCodeService.verifyAndConsume(req.getPhone(), SmsCodeService.SCENE_REGISTER, req.getCode());
        if (findByPhone(req.getPhone()) != null) {
            throw new BusinessException(UserErrorCode.PHONE_REGISTERED);
        }
        UserEntity user = new UserEntity();
        user.setPhone(req.getPhone());
        user.setNickname("用户" + req.getPhone().substring(req.getPhone().length() - 4));
        user.setStatus("NORMAL");
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册：uk_phone 兜底
            throw new BusinessException(UserErrorCode.PHONE_REGISTERED);
        }
        customerProfileService.ensureProfile(user.getId()); // ADR-027：注册即建档，CUSTOMER 码装配不再依赖补插
        events.publishEvent(new UserRegisteredEvent(user.getId(), req.getPhone(), LocalDateTime.now()));
    }

    public TokenResponse login(LoginRequest req) {
        smsCodeService.verifyAndConsume(req.getPhone(), SmsCodeService.SCENE_LOGIN, req.getCode());
        UserEntity user = findByPhone(req.getPhone());
        if (user == null) {
            throw new BusinessException(UserErrorCode.PHONE_NOT_REGISTERED);
        }
        if ("DISABLED".equals(user.getStatus())) {
            throw new BusinessException(UserErrorCode.ACCOUNT_DISABLED);
        }
        touchLogin(user.getId());
        AuthTokenApi.IssuedTokens tokens = tokenApi.issueTokens(user.getId(), null);
        whitelistRefresh(user.getId(), tokens);
        return TokenResponse.builder()
                .accessToken(tokens.accessToken())
                .refreshToken(tokens.refreshToken())
                .accessExpiresIn(tokens.accessExpiresIn())
                .userId(user.getId())
                .build();
    }

    public TokenResponse loginByPassword(PasswordLoginRequest req) {
        UserEntity user = findByPhone(req.getPhone());
        // 账号不存在与密码错误同一响应（20002），不泄露账号存在性
        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(UserErrorCode.PASSWORD_INCORRECT);
        }
        if ("DISABLED".equals(user.getStatus())) {
            throw new BusinessException(UserErrorCode.ACCOUNT_DISABLED);
        }
        touchLogin(user.getId());
        AuthTokenApi.IssuedTokens tokens = tokenApi.issueTokens(user.getId(), null);
        whitelistRefresh(user.getId(), tokens);
        return TokenResponse.builder()
                .accessToken(tokens.accessToken())
                .refreshToken(tokens.refreshToken())
                .accessExpiresIn(tokens.accessExpiresIn())
                .userId(user.getId())
                .build();
    }

    /** 忘记密码（ADR-011）：RESET 场景码换密码；未注册 20003 / 封禁 11005；成功清全部 refresh 白名单。 */
    @Transactional
    public void resetPassword(PasswordResetRequest req) {
        smsCodeService.verifyAndConsume(req.getPhone(), SmsCodeService.SCENE_RESET, req.getCode());
        UserEntity user = findByPhone(req.getPhone());
        if (user == null) {
            throw new BusinessException(UserErrorCode.PHONE_NOT_REGISTERED);
        }
        if ("DISABLED".equals(user.getStatus())) {
            throw new BusinessException(UserErrorCode.ACCOUNT_DISABLED);
        }
        writeNewPassword(user.getId(), req.getNewPassword());
        deleteRefreshWhitelist(user.getId());
    }

    /** 登录态改密（ADR-011）：旧密码错 20004；成功清全部 refresh 白名单（当前 access 自然过期前仍有效）。 */
    @Transactional
    public void changePassword(PasswordChangeRequest req) {
        Long userId = CurrentUserHolder.require().userId();
        UserEntity user = userMapper.selectById(userId);
        if (user == null || "DISABLED".equals(user.getStatus())) {
            throw new BusinessException(UserErrorCode.ACCOUNT_DISABLED);
        }
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(req.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException(UserErrorCode.OLD_PASSWORD_INCORRECT);
        }
        writeNewPassword(user.getId(), req.getNewPassword());
        deleteRefreshWhitelist(user.getId());
    }

    private void writeNewPassword(Long userId, String rawPassword) {
        UserEntity update = new UserEntity();
        update.setId(userId);
        update.setPasswordHash(passwordEncoder.encode(rawPassword));
        userMapper.updateById(update);
    }

    private void touchLogin(Long userId) {
        UserEntity touch = new UserEntity();
        touch.setId(userId);
        touch.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(touch);
    }

    public TokenResponse refresh(String refreshToken) {
        AuthTokenApi.ParsedToken parsed = tokenApi.parse(refreshToken)
                .orElseThrow(() -> new BusinessException(UserErrorCode.TOKEN_INVALID));
        if (parsed.type() != AuthTokenApi.TokenType.REFRESH) {
            throw new BusinessException(UserErrorCode.REFRESH_INVALID);
        }
        String whiteKey = String.format(KEY_REFRESH, parsed.userId(), parsed.jti());
        Boolean valid = redis.hasKey(whiteKey);
        if (!Boolean.TRUE.equals(valid)) {
            throw new BusinessException(UserErrorCode.REFRESH_INVALID);
        }
        UserEntity user = userMapper.selectById(parsed.userId());
        if (user == null || "DISABLED".equals(user.getStatus())) {
            throw new BusinessException(UserErrorCode.ACCOUNT_DISABLED);
        }
        // 轮换：旧 refresh 立即作废，重放旧令牌将命中 11004
        redis.delete(whiteKey);
        AuthTokenApi.IssuedTokens tokens = tokenApi.issueTokens(user.getId(), parsed.employeeId());
        whitelistRefresh(user.getId(), tokens);
        return TokenResponse.builder()
                .accessToken(tokens.accessToken())
                .refreshToken(tokens.refreshToken())
                .accessExpiresIn(tokens.accessExpiresIn())
                .userId(user.getId())
                .build();
    }

    /** 登出：语义=作废本会话全部凭据，直接复用 revokeAll（黑名单当前 access + 清该用户 refresh 白名单）。 */
    public void logout(String accessToken) {
        tokenApi.parse(accessToken).ifPresent(parsed -> revokeAll(parsed.userId(), parsed.jti(),
                parsed.expireAtEpochSecond() - java.time.Instant.now().getEpochSecond()));
    }

    /**
     * 作废某用户的全部会话凭据：黑名单当前 access（剩余 TTL）+ 清空其 refresh 白名单。
     *
     * <p>调用方：{@link #logout} 与 {@link #revokeAllSessions}（ADR-055 平台停用/离职端点——原"待接"条款
     * 的落点：被停用账号的存量 access token 仍可用到自然过期，但员工侧权限由
     * {@code PermissionAssembler.evictAll} 即时清空）。
     */
    public void revokeAll(Long userId, String currentAccessJti, long remainSeconds) {
        if (currentAccessJti != null && remainSeconds > 0) {
            redis.opsForValue().set(String.format(KEY_BLACKLIST, currentAccessJti), "1",
                    Duration.ofSeconds(remainSeconds));
        }
        deleteRefreshWhitelist(userId);
    }

    /** ADR-055：平台侧员工停用/离职的踢会话入口——清空 refresh 白名单；当前 access 由 TTL 自然过期。 */
    @Override
    public void revokeAllSessions(Long userId) {
        revokeAll(userId, null, 0);
    }

    private void whitelistRefresh(Long userId, AuthTokenApi.IssuedTokens tokens) {
        AuthTokenApi.ParsedToken refreshParsed = tokenApi.parse(tokens.refreshToken()).orElseThrow();
        redis.opsForValue().set(
                String.format(KEY_REFRESH, userId, refreshParsed.jti()), "1",
                Duration.ofSeconds(refreshTtl(refreshParsed)));
    }

    private long refreshTtl(AuthTokenApi.ParsedToken parsed) {
        return Math.max(1, parsed.expireAtEpochSecond() - java.time.Instant.now().getEpochSecond());
    }

    private void deleteRefreshWhitelist(Long userId) {
        String pattern = String.format(KEY_REFRESH, userId, "*");
        Set<String> keys = new HashSet<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(100).build())) {
            cursor.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private UserEntity findByPhone(String phone) {
        return userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getPhone, phone));
    }
}
