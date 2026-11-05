package com.petplatform.appointment.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.petplatform.appointment.api.dto.AppointmentCreateRequest;
import com.petplatform.appointment.api.dto.AppointmentCreatedView;
import com.petplatform.appointment.api.event.AppointmentCreatedEvent;
import com.petplatform.appointment.domain.AppointmentEntity;
import com.petplatform.appointment.mapper.AppointmentCommandMapper;
import com.petplatform.catalog.api.dto.StoreServiceView;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.time.BusinessDates;
import com.petplatform.order.api.OrderCommandApi;
import com.petplatform.order.api.dto.CreateOrderCommand;
import com.petplatform.order.api.dto.OrderView;
import com.petplatform.payment.api.PaymentCommandApi;
import com.petplatform.payment.api.dto.PrepayView;
import com.petplatform.pet.api.PetErrorCode;
import com.petplatform.pet.api.PetQueryApi;
import com.petplatform.pet.api.dto.PetSummary;
import com.petplatform.schedule.api.ResourceErrorCode;
import com.petplatform.schedule.api.ResourceOccupancyApi;
import com.petplatform.schedule.api.ScheduleLockApi;
import com.petplatform.schedule.api.dto.KennelResourceView;
import com.petplatform.schedule.api.dto.ScheduleLockView;
import com.petplatform.store.api.dto.StoreView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 创建预约的事务段（ADR-016 §6；独立 bean——自调用会绕过 @Transactional 代理）。
 * 同事务：笼位/时段占用 → 插预约 → 建订单 → 回填 order_id → 预下单流水（PROCESSING）；任一步失败整体回滚（占用不留半截）。
 * 支付拉起失败后可经 POST /orders/{id}/pay 重新预下单（PROCESSING 流水复用/新建）。
 */
@Service
public class AppointmentCreateTxService {

    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AppointmentCommandMapper appointmentMapper;
    private final OrderCommandApi orderCommandApi;
    private final PaymentCommandApi paymentCommandApi;
    private final ResourceOccupancyApi occupancyApi;
    private final ScheduleLockApi scheduleLockApi;
    private final AvailabilityService availability;
    private final PetQueryApi petApi;
    private final org.springframework.context.ApplicationEventPublisher events;

    public AppointmentCreateTxService(AppointmentCommandMapper appointmentMapper,
                                      OrderCommandApi orderCommandApi, PaymentCommandApi paymentCommandApi,
                                      ResourceOccupancyApi occupancyApi, ScheduleLockApi scheduleLockApi,
                                      AvailabilityService availability, PetQueryApi petApi,
                                      org.springframework.context.ApplicationEventPublisher events) {
        this.appointmentMapper = appointmentMapper;
        this.orderCommandApi = orderCommandApi;
        this.paymentCommandApi = paymentCommandApi;
        this.occupancyApi = occupancyApi;
        this.scheduleLockApi = scheduleLockApi;
        this.availability = availability;
        this.petApi = petApi;
        this.events = events;
    }

    @Transactional
    public AppointmentCreatedView doCreate(Long userId, AppointmentCreateRequest req, StoreServiceView svc,
                                           StoreView store, PetSummary pet, boolean multiDay,
                                           LocalDate serviceDate, String source) {
        // 与宠物删除共用 pet_pet 行锁：删除先提交 → 这里拿不到宠物；本事务先提交 → 删除在锁后重查阻断记录。
        PetSummary lockedPet = petApi.lockForUse(req.getPetId());
        if (lockedPet == null || !"NORMAL".equals(lockedPet.getStatus())) {
            throw BusinessException.of(PetErrorCode.PET_FORBIDDEN);
        }
        if (multiDay && lockedPet.getSizeCategory() == null) {
            throw new BusinessException(com.petplatform.appointment.api.AppointmentErrorCode.PET_NOT_BOOKABLE);
        }
        pet = lockedPet;
        LocalDateTime now = LocalDateTime.now(BusinessDates.ZONE);
        long appointmentId = IdWorker.getId(); // 预生成：占用行需挂 appointment_id（§5.2 顺序）
        LocalTime start = null;
        LocalTime end = null;
        if (multiDay) {
            List<KennelResourceView> free = availability.freeKennels(svc.getStoreId(), pet.getSpecies(),
                    pet.getSizeCategory(), req.getBoardingCheckinDate(), req.getBoardingCheckoutDate());
            if (free.isEmpty()) {
                throw new BusinessException(ResourceErrorCode.KENNEL_CAPACITY_INSUFFICIENT);
            }
            Long kennelId = free.get(0).getId();
            KennelResourceView locked = occupancyApi.lockResourceForUse(svc.getStoreId(), kennelId);
            if (locked == null || !"AVAILABLE".equals(locked.getStatus())) {
                // 读侧选中的笼位在写占用前被停用/删除：按现有容量语义失败，不做自动换位。
                throw new BusinessException(ResourceErrorCode.KENNEL_CAPACITY_INSUFFICIENT);
            }
            try {
                occupancyApi.confirmOrMoveKennel(kennelId, appointmentId,
                        req.getBoardingCheckinDate(), req.getBoardingCheckoutDate());
            } catch (BusinessException e) {
                if (e.getCode() == ResourceErrorCode.KENNEL_MOVE_CONFLICT.getCode()) {
                    throw new BusinessException(ResourceErrorCode.KENNEL_CAPACITY_INSUFFICIENT);
                }
                throw e;
            }
        } else {
            start = req.getStartTime();
            end = start.plusMinutes(svc.getDurationMin());
            if (req.getEmployeeId() != null) {
                ScheduleLockView shift = scheduleLockApi.lockScheduleForCreate(req.getEmployeeId(), serviceDate);
                availability.requireEmployeeBookable(shift, store, serviceDate,
                        AvailabilityService.slotOf(start), slotOfEnd(start, svc.getDurationMin()),
                        AvailabilityService.requiredSkillIds(svc), req.getEmployeeId());
            }
            String type = svc.getRequiredResourceType();
            if (type != null && !type.isBlank()) {
                Long resourceId = availability.pickFreeResource(type, serviceDate, svc.getStoreId(),
                        AvailabilityService.slotOf(start), slotOfEnd(start, svc.getDurationMin()));
                if (resourceId == null) {
                    throw new BusinessException(ResourceErrorCode.RESOURCE_FULL);
                }
                KennelResourceView locked = occupancyApi.lockResourceForUse(svc.getStoreId(), resourceId);
                if (locked == null || !"AVAILABLE".equals(locked.getStatus())) {
                    // 读侧选中后资源被停用：按现有“该时段已约满”语义失败，不做自动换位。
                    throw new BusinessException(ResourceErrorCode.RESOURCE_FULL);
                }
                List<Integer> slots = new ArrayList<>();
                for (int s = AvailabilityService.slotOf(start); s < slotOfEnd(start, svc.getDurationMin()); s++) {
                    slots.add(s);
                }
                occupancyApi.occupyTimeSlots(resourceId, appointmentId, serviceDate, slots);
            }
        }
        AppointmentEntity apt = new AppointmentEntity();
        apt.setId(appointmentId);
        apt.setAppointmentNo("AP" + now.format(NO_DATE) + appointmentId);
        apt.setUserId(userId);
        apt.setPetId(req.getPetId());
        apt.setStoreId(svc.getStoreId());
        apt.setStoreServiceId(svc.getId());
        apt.setEmployeeId(multiDay ? null : req.getEmployeeId());
        apt.setServiceDate(serviceDate);
        apt.setStartTime(start);
        apt.setEndTime(end);
        apt.setBoardingCheckinDate(multiDay ? req.getBoardingCheckinDate() : null);
        apt.setBoardingCheckoutDate(multiDay ? req.getBoardingCheckoutDate() : null);
        apt.setServiceNameSnapshot(svc.getServiceName());
        // ADR-025：MULTI_DAY 的 cat_store_service.price 是**每日单价**，总价=日单价×入住晚数
        // （晚数=checkout-checkin，与 ADR-015 笼位占用 [checkin,checkout) 逐日一行同口径）；
        // price_snapshot 恒等于订单总额（INSTANT 即单价×1），日单价与天数另存 item_snapshot 供明细展示。
        int boardingDays = multiDay
                ? (int) ChronoUnit.DAYS.between(req.getBoardingCheckinDate(), req.getBoardingCheckoutDate())
                : 1;
        long totalAmount = svc.getPrice() * boardingDays;
        apt.setPriceSnapshot(totalAmount);
        apt.setDurationSnapshot(svc.getDurationMin());
        // S7（ADR-060）：取消政策与价格/时长同批冻结——写"当时有效值"（缺省/钳制复用 CancelPolicyDefaults，
        // 取消侧对快照零解释）；此后 cat_store_service 再被改也不影响本单退款
        apt.setCancelFreeHoursSnapshot(CancelPolicyDefaults.freeHours(svc));
        apt.setCancelPenaltyRateSnapshot(CancelPolicyDefaults.penaltyRate(svc));
        apt.setStatus("PENDING_PAYMENT");
        apt.setRemark(req.getRemark());
        apt.setExpireAt(now.plusMinutes(availability.configInt("appointment.pay_timeout_minutes", 15)));
        // CUSTOMER=顾客自助；RECEPTIONIST=门店代客（B 端新增预约）——列宽 16、V1 注释即此二值
        apt.setSource(source);
        apt.setVersion(0);
        appointmentMapper.insert(apt);

        OrderView order = orderCommandApi.createForAppointment(CreateOrderCommand.builder()
                .appointmentId(appointmentId)
                .userId(userId)
                .storeId(svc.getStoreId())
                .amount(totalAmount)
                .payExpireAt(apt.getExpireAt())
                .itemSnapshot(Map.of("serviceName", svc.getServiceName(),
                        "durationMin", svc.getDurationMin(), "price", svc.getPrice(), "days", boardingDays))
                .build());
        AppointmentEntity link = new AppointmentEntity();
        link.setId(appointmentId);
        link.setOrderId(order.getId());
        appointmentMapper.updateById(link);

        // ADR-052：事件带上执行人与文案要素（服务/日期/时段），执行人通知桥零回查
        events.publishEvent(new AppointmentCreatedEvent(appointmentId, userId, svc.getStoreId(),
                apt.getEmployeeId(), apt.getServiceNameSnapshot(), serviceDate, start, now));

        PrepayView prepay = paymentCommandApi.createPrepay(order.getId(), order.getAmount(),
                svc.getServiceName());
        return AppointmentCreatedView.builder()
                .appointmentId(appointmentId).appointmentNo(apt.getAppointmentNo()).status(apt.getStatus())
                .expireAt(apt.getExpireAt()).serviceNameSnapshot(apt.getServiceNameSnapshot())
                .priceSnapshot(apt.getPriceSnapshot()).durationSnapshot(apt.getDurationSnapshot())
                .orderId(order.getId()).orderNo(order.getOrderNo()).amount(order.getAmount())
                .paymentParams(prepay.getParams())
                .build();
    }

    private int slotOfEnd(LocalTime start, int durationMin) {
        return AvailabilityService.slotOf(start) + durationMin / AvailabilityService.SLOT_MINUTES;
    }
}
