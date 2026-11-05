package com.petplatform.appointment.service;

import com.petplatform.appointment.api.AppointmentErrorCode;
import com.petplatform.appointment.api.dto.AppointmentSelfView;
import com.petplatform.appointment.api.dto.ModifyAppointmentRequest;
import com.petplatform.appointment.domain.AppointmentEntity;
import com.petplatform.appointment.mapper.AppointmentCommandMapper;
import com.petplatform.catalog.api.dto.StoreServiceView;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.time.BusinessDates;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.pet.api.PetQueryApi;
import com.petplatform.pet.api.dto.PetSummary;
import com.petplatform.store.api.StoreQueryApi;
import com.petplatform.store.api.dto.StoreView;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 改期编排（ADR-026，AppointmentCreateService 同构但只保留排期相关闸）：
 * C1 归属 → PENDING_PAYMENT 闸（50003）→ C2~C7 同一算法体；
 * 显式豁免 C8 未付上限/日计数/C9 医疗查重（理由见 ADR §2）→ 新日期店×日锁 → 事务段 → 时限重置。
 */
@Slf4j
@Service
public class AppointmentModifyService {

    private static final Duration REQ_TTL = Duration.ofMinutes(5);

    private final AppointmentCommandMapper appointmentMapper;
    private final AppointmentModifyTxService txService;
    private final AppointmentQueryService queryService;
    private final AvailabilityService availability;
    private final StoreQueryApi storeApi;
    private final PetQueryApi petApi;
    private final EmployeeQueryApi employeeApi;
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;

    public AppointmentModifyService(AppointmentCommandMapper appointmentMapper,
                                    AppointmentModifyTxService txService, AppointmentQueryService queryService,
                                    AvailabilityService availability, StoreQueryApi storeApi,
                                    PetQueryApi petApi, EmployeeQueryApi employeeApi,
                                    StringRedisTemplate redis, RedissonClient redisson) {
        this.appointmentMapper = appointmentMapper;
        this.txService = txService;
        this.queryService = queryService;
        this.availability = availability;
        this.storeApi = storeApi;
        this.petApi = petApi;
        this.employeeApi = employeeApi;
        this.redis = redis;
        this.redisson = redisson;
    }

    public AppointmentSelfView modify(AuthPrincipal principal, Long appointmentId, ModifyAppointmentRequest req) {
        Boolean first = redis.opsForValue().setIfAbsent(
                "req:" + principal.userId() + ":" + req.getRequestId(), "1", REQ_TTL);
        if (!Boolean.TRUE.equals(first)) {
            throw new BusinessException(CommonErrorCode.DUPLICATE_REQUEST, "请求已处理，请勿重复提交");
        }
        AppointmentEntity apt = appointmentId == null ? null : appointmentMapper.selectById(appointmentId);
        if (apt == null || !principal.userId().equals(apt.getUserId())) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        if (!"PENDING_PAYMENT".equals(apt.getStatus())) {
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION, "仅未支付预约可改期");
        }
        StoreServiceView svc = availability.requireBookableService(apt.getStoreServiceId());
        StoreView store = storeApi.store(svc.getStoreId());
        boolean multiDay = "MULTI_DAY".equals(svc.getFulfillmentType());
        PetSummary pet = petApi.summaries(List.of(apt.getPetId())).stream().findFirst().orElse(null);
        if (pet == null || !"NORMAL".equals(pet.getStatus())
                || (multiDay && pet.getSizeCategory() == null)) {
            throw new BusinessException(AppointmentErrorCode.PET_NOT_BOOKABLE);
        }
        LocalDateTime now = LocalDateTime.now(BusinessDates.ZONE);
        LocalDate newDate = multiDay ? req.getBoardingCheckinDate() : req.getServiceDate();
        validateFields(req, svc, store, multiDay, now);

        LocalDateTime expireAt = now.plusMinutes(availability.configInt("appointment.pay_timeout_minutes", 15));
        RLock lock = redisson.getLock("lock:apt:" + svc.getStoreId() + ":" + newDate);
        boolean locked;
        try {
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(CommonErrorCode.DUPLICATE_REQUEST, "系统繁忙，请稍后重试");
        }
        if (!locked) {
            throw new BusinessException(CommonErrorCode.DUPLICATE_REQUEST, "系统繁忙，请稍后重试");
        }
        try {
            txService.doModify(apt, req, svc, store, multiDay, expireAt, pet);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        return queryService.selfDetail(principal.userId(), appointmentId);
    }

    /** 字段白名单+成对+窗口/对齐/营业窗（与创建同口径，ADR-026 §1/§2）。 */
    private void validateFields(ModifyAppointmentRequest req, StoreServiceView svc, StoreView store,
                                boolean multiDay, LocalDateTime now) {
        LocalDate date;
        if (multiDay) {
            if (req.getServiceDate() != null || req.getStartTime() != null) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "托管改期只改入住/离店日期");
            }
            if (req.getBoardingCheckinDate() == null || req.getBoardingCheckoutDate() == null) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "托管改期须成对提交入住/离店日期");
            }
            if (!req.getBoardingCheckoutDate().isAfter(req.getBoardingCheckinDate())) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "离店日期须晚于入住日期");
            }
            date = req.getBoardingCheckinDate();
        } else {
            if (req.getBoardingCheckinDate() != null || req.getBoardingCheckoutDate() != null) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "即时预约改期不传托管日期");
            }
            if (req.getServiceDate() == null || req.getStartTime() == null) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "服务日期与开始时间必填");
            }
            if (req.getStartTime().getMinute() % 30 != 0 || req.getStartTime().getSecond() != 0) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "开始时间须30分钟对齐");
            }
            date = req.getServiceDate();
            int dur = svc.getDurationMin() == null ? 0 : svc.getDurationMin();
            int fromSlot = AvailabilityService.slotOf(req.getStartTime());
            boolean inWindow = availability.candidateWindows(store, dur, date, now).stream()
                    .anyMatch(w -> w[0] == fromSlot && w[1] == fromSlot + dur / AvailabilityService.SLOT_MINUTES);
            if (!inWindow) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "所选时段不在可约营业时间窗内");
            }
        }
        LocalDate today = now.toLocalDate();
        int maxAdvance = availability.configInt("appointment.max_advance_days", 30);
        if (date.isBefore(today) || date.isAfter(today.plusDays(maxAdvance))) {
            throw new BusinessException(AppointmentErrorCode.OUT_OF_BOOKING_WINDOW);
        }
        if (req.getEmployeeId() != null) {
            if (!Boolean.TRUE.equals(svc.getAllowUserPickEmployee())) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "该服务不支持用户指定员工");
            }
            if (!employeeApi.isActiveInStore(req.getEmployeeId(), svc.getStoreId())) {
                throw new BusinessException(AppointmentErrorCode.EMPLOYEE_NOT_WORKING, "员工非该店在职");
            }
        }
    }
}
