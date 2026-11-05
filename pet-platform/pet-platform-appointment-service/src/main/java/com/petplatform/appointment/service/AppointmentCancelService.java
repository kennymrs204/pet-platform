package com.petplatform.appointment.service;

import com.petplatform.appointment.api.dto.AppointmentCancelView;
import com.petplatform.appointment.domain.AppointmentEntity;
import com.petplatform.appointment.mapper.AppointmentCommandMapper;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.security.AuthPrincipal;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 用户取消编排（ADR-023 §2，先例 AppointmentCreateService）：
 * requestId SETNX 幂等 → 归属快照读（非本人=10004 按不存在）→ 事务段。
 */
@Service
public class AppointmentCancelService {

    private static final Duration REQ_TTL = Duration.ofMinutes(5);

    private final AppointmentCommandMapper appointmentMapper;
    private final AppointmentCancelTxService txService;
    private final StringRedisTemplate redis;

    public AppointmentCancelService(AppointmentCommandMapper appointmentMapper,
                                    AppointmentCancelTxService txService, StringRedisTemplate redis) {
        this.appointmentMapper = appointmentMapper;
        this.txService = txService;
        this.redis = redis;
    }

    public AppointmentCancelView cancel(AuthPrincipal principal, Long appointmentId,
                                        String requestId, String reason) {
        Boolean first = redis.opsForValue().setIfAbsent(
                "req:" + principal.userId() + ":" + requestId, "1", REQ_TTL);
        if (!Boolean.TRUE.equals(first)) {
            throw new BusinessException(CommonErrorCode.DUPLICATE_REQUEST, "请求已处理，请勿重复提交");
        }
        AppointmentEntity apt = appointmentId == null ? null : appointmentMapper.selectById(appointmentId);
        if (apt == null || !principal.userId().equals(apt.getUserId())) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        String text = reason == null || reason.isBlank() ? "用户主动取消" : reason.trim();
        return txService.doCancel(apt, text);
    }
}
