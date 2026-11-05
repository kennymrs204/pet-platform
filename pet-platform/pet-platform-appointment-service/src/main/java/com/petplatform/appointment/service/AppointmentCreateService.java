package com.petplatform.appointment.service;

import com.petplatform.appointment.api.AppointmentErrorCode;
import com.petplatform.appointment.api.dto.AppointmentCreateRequest;
import com.petplatform.appointment.api.dto.AppointmentCreatedView;
import com.petplatform.appointment.mapper.AppointmentCommandMapper;
import com.petplatform.catalog.api.dto.StoreServiceView;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.common.time.BusinessDates;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.order.api.OrderQueryApi;
import com.petplatform.pet.api.PetErrorCode;
import com.petplatform.pet.api.PetQueryApi;
import com.petplatform.pet.api.dto.PetSummary;
import com.petplatform.store.api.StoreQueryApi;
import com.petplatform.store.api.dto.StoreView;
import com.petplatform.user.api.UserAccountApi;
import com.petplatform.user.api.UserPetApi;
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
 * 创建预约编排（第五阶段 §5.2 + ADR-016 §6）：
 * C1~C9 前置快速失败 → requestId SETNX 幂等 → 店×日 Redisson 锁 → 事务段（AppointmentCreateTxService）
 * → 日计数递增。锁是体验优化，正确性由唯一索引/行锁保证（§5.3）。
 */
@Slf4j
@Service
public class AppointmentCreateService {

    private static final Duration REQ_TTL = Duration.ofMinutes(5);

    private final AvailabilityService availability;
    private final AppointmentCreateTxService txService;
    private final AppointmentCommandMapper appointmentMapper;
    private final UserPetApi userPetApi;
    private final UserAccountApi userAccountApi;
    private final EmployeeQueryApi employeeApi;
    private final PetQueryApi petApi;
    private final StoreQueryApi storeApi;
    private final OrderQueryApi orderQueryApi;
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;

    public AppointmentCreateService(AvailabilityService availability, AppointmentCreateTxService txService,
                                    AppointmentCommandMapper appointmentMapper, UserPetApi userPetApi,
                                    UserAccountApi userAccountApi, EmployeeQueryApi employeeApi,
                                    PetQueryApi petApi, StoreQueryApi storeApi, OrderQueryApi orderQueryApi,
                                    StringRedisTemplate redis, RedissonClient redisson) {
        this.availability = availability;
        this.txService = txService;
        this.appointmentMapper = appointmentMapper;
        this.userPetApi = userPetApi;
        this.userAccountApi = userAccountApi;
        this.employeeApi = employeeApi;
        this.petApi = petApi;
        this.storeApi = storeApi;
        this.orderQueryApi = orderQueryApi;
        this.redis = redis;
        this.redisson = redisson;
    }

    /** C 端入口（顾客给自己建单）：下单人 = 当前登录用户，source=CUSTOMER。 */
    public AppointmentCreatedView create(AppointmentCreateRequest req) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return core(principal.userId(), req, "CUSTOMER", null);
    }

    /**
     * B 端入口（门店代客建单）：下单人 = 顾客（而非当前登录员工），source=RECEPTIONIST 可追溯；
     * 服务必须属于 storeId（防跨店代建，10004 同枚举口径）。顾客"在本店客户集"的校验由调用方
     * （app 装配层 requireCustomer）先行——本层不感知客户集口径。
     */
    public AppointmentCreatedView createForCustomer(Long storeId, Long customerUserId, AppointmentCreateRequest req) {
        return core(customerUserId, req, "RECEPTIONIST", storeId);
    }

    private AppointmentCreatedView core(Long userId, AppointmentCreateRequest req, String source, Long requiredStoreId) {
        // C1 账号状态 + 宠物归属（20101）
        if (!"NORMAL".equals(userAccountApi.statusOf(userId))) {
            throw new BusinessException(com.petplatform.user.api.UserErrorCode.ACCOUNT_DISABLED);
        }
        if (!userPetApi.canManage(userId, req.getPetId())) {
            throw BusinessException.of(PetErrorCode.PET_FORBIDDEN);
        }
        // C3/C4 服务与门店（31001/30001）；B 端追加"服务属本店"闸（C 端 requiredStoreId=null 不校验——顾客可约任意门店）
        StoreServiceView svc = availability.requireBookableService(req.getStoreServiceId());
        if (requiredStoreId != null && !requiredStoreId.equals(svc.getStoreId())) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        StoreView store = storeApi.store(svc.getStoreId());
        // C2 宠物状态（20102）
        PetSummary pet = petApi.summaries(List.of(req.getPetId())).stream().findFirst()
                .orElseThrow(() -> BusinessException.of(PetErrorCode.PET_FORBIDDEN));
        boolean multiDay = "MULTI_DAY".equals(svc.getFulfillmentType());
        if (!"NORMAL".equals(pet.getStatus()) || (multiDay && pet.getSizeCategory() == null)) {
            throw new BusinessException(AppointmentErrorCode.PET_NOT_BOOKABLE);
        }
        LocalDateTime now = LocalDateTime.now(BusinessDates.ZONE);
        LocalDate today = now.toLocalDate();
        int maxAdvance = availability.configInt("appointment.max_advance_days", 30);
        LocalDate serviceDate;
        if (multiDay) {
            if (req.getServiceDate() != null || req.getStartTime() != null) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "托管预约不传时段字段");
            }
            if (req.getBoardingCheckinDate() == null || req.getBoardingCheckoutDate() == null) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "托管预约须填入住/离店日期");
            }
            if (!req.getBoardingCheckoutDate().isAfter(req.getBoardingCheckinDate())) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "离店日期须晚于入住日期");
            }
            requireWindow(req.getBoardingCheckinDate(), today, maxAdvance);
            serviceDate = req.getBoardingCheckinDate();
        } else {
            if (req.getBoardingCheckinDate() != null || req.getBoardingCheckoutDate() != null) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "即时预约不传托管日期");
            }
            if (req.getServiceDate() == null || req.getStartTime() == null) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "服务日期与开始时间必填");
            }
            if (req.getStartTime().getMinute() % 30 != 0 || req.getStartTime().getSecond() != 0) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "开始时间须30分钟对齐");
            }
            requireWindow(req.getServiceDate(), today, maxAdvance);
            int dur = svc.getDurationMin() == null ? 0 : svc.getDurationMin();
            int fromSlot = AvailabilityService.slotOf(req.getStartTime());
            boolean inWindow = availability.candidateWindows(store, dur, req.getServiceDate(), now)
                    .stream().anyMatch(w -> w[0] == fromSlot
                            && w[1] == fromSlot + dur / AvailabilityService.SLOT_MINUTES);
            if (!inWindow) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "所选时段不在可约营业时间窗内");
            }
            serviceDate = req.getServiceDate();
        }
        // C7 指定员工（allowUserPick 闸 + 在职；班次/重叠/技能在事务段锁内复核）
        if (req.getEmployeeId() != null) {
            if (!Boolean.TRUE.equals(svc.getAllowUserPickEmployee())) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "该服务不支持用户指定员工");
            }
            if (!employeeApi.isActiveInStore(req.getEmployeeId(), svc.getStoreId())) {
                throw new BusinessException(AppointmentErrorCode.EMPLOYEE_NOT_WORKING, "员工非该店在职");
            }
        }
        // C8 未支付上限 + 日创建上限（§5.4）
        if (orderQueryApi.countUnpaid(userId) >= availability.configInt("order.unpaid_limit_per_user", 3)) {
            throw new BusinessException(AppointmentErrorCode.TOO_MANY_UNPAID);
        }
        String dailyKey = "apt:cnt:" + userId + ":" + svc.getStoreId() + ":" + today;
        String cnt = redis.opsForValue().get(dailyKey);
        if (cnt != null && Long.parseLong(cnt)
                >= availability.configInt("order.daily_create_limit_per_user_store", 10)) {
            throw new BusinessException(AppointmentErrorCode.DAILY_CREATE_LIMIT);
        }
        // C9 医疗类专用（第五阶段 §5.1"医疗类服务"限定，防重复挂号；非医疗类不拦）
        if ("MEDICAL".equals(svc.getCategory())
                && appointmentMapper.countRecentSameService(req.getPetId(), req.getStoreServiceId(),
                        now.minusHours(24)) > 0) {
            throw new BusinessException(CommonErrorCode.DUPLICATE_REQUEST, "该宠物近期已有相同医疗预约未完成");
        }
        // requestId 幂等（W 裁决：SETNX req:{userId}:{requestId} TTL 5min）
        Boolean first = redis.opsForValue().setIfAbsent(
                "req:" + userId + ":" + req.getRequestId(), "1", REQ_TTL);
        if (!Boolean.TRUE.equals(first)) {
            throw new BusinessException(CommonErrorCode.DUPLICATE_REQUEST, "请求已处理，请勿重复提交");
        }
        // 店×日锁（§5.3 粒度）
        RLock lock = redisson.getLock("lock:apt:" + svc.getStoreId() + ":" + serviceDate);
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
            AppointmentCreatedView view = txService.doCreate(userId, req, svc, store, pet, multiDay, serviceDate, source);
            Long total = redis.opsForValue().increment(dailyKey);
            if (total != null && total == 1L) {
                redis.expire(dailyKey, Duration.ofDays(1));
            }
            return view;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void requireWindow(LocalDate date, LocalDate today, int maxAdvance) {
        if (date.isBefore(today) || date.isAfter(today.plusDays(maxAdvance))) {
            throw new BusinessException(AppointmentErrorCode.OUT_OF_BOOKING_WINDOW);
        }
    }
}
