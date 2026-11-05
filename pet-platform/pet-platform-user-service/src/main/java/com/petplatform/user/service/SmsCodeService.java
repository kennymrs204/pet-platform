package com.petplatform.user.service;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.user.api.UserErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 短信验证码（第八阶段 §4.1 + ADR-011 场景隔离）：
 * - key 按场景隔离 sms:code:{scene}:{phone}；60s 间隔按场景独立，日上限 10 保持手机号全局。
 * - 爆破防护：同场景连错 5 次作废当前码（ADR-011 D1）。
 * - 校验通过即销毁（一次性）；失败抛 11001。
 * 网关未接入，开发期以日志代替发送（[DEV-SMS]）。
 */
@Service
public class SmsCodeService {

    public static final String SCENE_LOGIN = "LOGIN";
    public static final String SCENE_REGISTER = "REGISTER";
    public static final String SCENE_RESET = "RESET";

    private static final Logger log = LoggerFactory.getLogger(SmsCodeService.class);

    private static final String KEY_CODE = "sms:code:%s:%s";
    private static final String KEY_INTERVAL = "sms:interval:%s:%s";
    private static final String KEY_DAILY = "sms:daily:%s";
    private static final String KEY_FAIL = "sms:fail:%s:%s";
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final int DAILY_LIMIT = 10;
    private static final int MAX_WRONG_ATTEMPTS = 5;

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    public SmsCodeService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void send(String phone, String scene) {
        String sc = normalize(scene);
        String intervalKey = String.format(KEY_INTERVAL, sc, phone);
        Boolean first = redis.opsForValue().setIfAbsent(intervalKey, "1", Duration.ofSeconds(60));
        if (!Boolean.TRUE.equals(first)) {
            throw new BusinessException(CommonErrorCode.DUPLICATE_REQUEST, "发送过于频繁，请 60 秒后再试");
        }
        String dailyKey = String.format(KEY_DAILY, phone);
        Long count = redis.opsForValue().increment(dailyKey);
        if (count != null && count == 1L) {
            redis.expire(dailyKey, Duration.between(LocalDateTime.now(),
                    LocalDate.now().plusDays(1).atStartOfDay()));
        }
        if (count != null && count > DAILY_LIMIT) {
            throw new BusinessException(CommonErrorCode.DUPLICATE_REQUEST, "今日验证码发送次数已达上限");
        }
        String code = String.valueOf(random.nextInt(100000, 1000000));
        redis.opsForValue().set(String.format(KEY_CODE, sc, phone), code, CODE_TTL);
        redis.delete(String.format(KEY_FAIL, sc, phone));
        log.info("[DEV-SMS] 向 {} 发送{}场景验证码 {}（5 分钟有效，短信网关未接入）", phone, sc, code);
    }

    /** 校验通过即销毁当前场景码并清失败计数；失败抛 11001，连错 5 次作废该码。 */
    public void verifyAndConsume(String phone, String scene, String code) {
        String sc = normalize(scene);
        String codeKey = String.format(KEY_CODE, sc, phone);
        String cached = redis.opsForValue().get(codeKey);
        if (cached == null || !cached.equals(code)) {
            String failKey = String.format(KEY_FAIL, sc, phone);
            Long fails = redis.opsForValue().increment(failKey);
            if (fails != null && fails == 1L) {
                redis.expire(failKey, CODE_TTL);
            }
            if (fails != null && fails >= MAX_WRONG_ATTEMPTS) {
                redis.delete(codeKey);
                redis.delete(failKey);
                log.warn("[SMS] {} 场景 {} 连错 {} 次，验证码已作废", sc, phone, MAX_WRONG_ATTEMPTS);
            }
            throw new BusinessException(UserErrorCode.SMS_CODE_INVALID);
        }
        redis.delete(codeKey);
        redis.delete(String.format(KEY_FAIL, sc, phone));
    }

    private String normalize(String scene) {
        return scene == null || scene.isBlank() ? SCENE_LOGIN : scene;
    }
}
