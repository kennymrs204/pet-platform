package com.petplatform.appointment.service;

import com.petplatform.appointment.api.AppointmentErrorCode;
import com.petplatform.appointment.api.dto.ModifyAppointmentRequest;
import com.petplatform.appointment.domain.AppointmentEntity;
import com.petplatform.appointment.mapper.AppointmentCommandMapper;
import com.petplatform.catalog.api.dto.StoreServiceView;
import com.petplatform.common.error.BusinessException;
import com.petplatform.schedule.api.ResourceErrorCode;
import com.petplatform.schedule.api.ResourceOccupancyApi;
import com.petplatform.schedule.api.ScheduleLockApi;
import com.petplatform.schedule.api.dto.KennelResourceView;
import com.petplatform.schedule.api.dto.ScheduleLockView;
import com.petplatform.store.api.dto.StoreView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 改期事务段（ADR-026 §3，独立 bean——自调用会绕过 @Transactional 代理，AppointmentCreateTxService 先例）：
 * 选目标（排除自身旧占用）→ 锁目标资源 → 复检 status 与其他预约占用 → 释放旧占用 → 写新占用
 * → updateModify 条件更新（0 行=并发取消/推进 → 50003）。任一步失败整体回滚：旧占用行随事务还原。
 * 锁顺序固定为“资源行 → 占用行”，与停用/创建/入住一致；不发事件（未付无下游，§3）。
 */
@Slf4j
@Service
public class AppointmentModifyTxService {

    private final AppointmentCommandMapper appointmentMapper;
    private final ResourceOccupancyApi occupancyApi;
    private final ScheduleLockApi scheduleLockApi;
    private final AvailabilityService availability;

    public AppointmentModifyTxService(AppointmentCommandMapper appointmentMapper,
                                      ResourceOccupancyApi occupancyApi, ScheduleLockApi scheduleLockApi,
                                      AvailabilityService availability) {
        this.appointmentMapper = appointmentMapper;
        this.occupancyApi = occupancyApi;
        this.scheduleLockApi = scheduleLockApi;
        this.availability = availability;
    }

    @Transactional
    public void doModify(AppointmentEntity apt, ModifyAppointmentRequest req, StoreServiceView svc,
                         StoreView store, boolean multiDay, LocalDateTime expireAt,
                         com.petplatform.pet.api.dto.PetSummary pet) {
        if (multiDay) {
            // 选目标时排除本预约自身旧 DAY 预占；资源锁定/复检全部通过后才释放旧占用。
            List<KennelResourceView> free = availability.freeKennels(svc.getStoreId(),
                    pet.getSpecies(), pet.getSizeCategory(),
                    req.getBoardingCheckinDate(), req.getBoardingCheckoutDate(), apt.getId());
            if (free.isEmpty()) {
                throw new BusinessException(ResourceErrorCode.KENNEL_CAPACITY_INSUFFICIENT);
            }
            Long kennelId = free.get(0).getId();
            KennelResourceView locked = occupancyApi.lockResourceForUse(svc.getStoreId(), kennelId);
            if (locked == null || !"AVAILABLE".equals(locked.getStatus())) {
                throw new BusinessException(ResourceErrorCode.KENNEL_CAPACITY_INSUFFICIENT);
            }
            if (occupancyApi.hasOtherDayOccupancyForUpdate(kennelId,
                    req.getBoardingCheckinDate(), req.getBoardingCheckoutDate(), apt.getId())) {
                throw new BusinessException(ResourceErrorCode.KENNEL_CAPACITY_INSUFFICIENT);
            }
            occupancyApi.releaseAll(apt.getId());
            try {
                occupancyApi.confirmOrMoveKennel(kennelId, apt.getId(),
                        req.getBoardingCheckinDate(), req.getBoardingCheckoutDate());
            } catch (BusinessException e) {
                if (e.getCode() == ResourceErrorCode.KENNEL_MOVE_CONFLICT.getCode()) {
                    throw new BusinessException(ResourceErrorCode.KENNEL_CAPACITY_INSUFFICIENT);
                }
                throw e;
            }
            if (appointmentMapper.updateModifyBoarding(apt.getId(), apt.getUserId(),
                    req.getBoardingCheckinDate(), req.getBoardingCheckoutDate(), expireAt) == 0) {
                throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION);
            }
            return;
        }

        LocalDate date = req.getServiceDate();
        LocalTime start = req.getStartTime();
        LocalTime end = start.plusMinutes(svc.getDurationMin() == null ? 0 : svc.getDurationMin());
        int fromSlot = AvailabilityService.slotOf(start);
        int toSlot = fromSlot + (svc.getDurationMin() == null ? 0 : svc.getDurationMin())
                / AvailabilityService.SLOT_MINUTES;
        if (req.getEmployeeId() != null) {
            // 员工校验不依赖资源占用；放在释放旧占用之前，失败时无需删除任何占用。
            ScheduleLockView shift = scheduleLockApi.lockScheduleForCreate(req.getEmployeeId(), date);
            availability.requireEmployeeBookable(shift, store, date, fromSlot, toSlot,
                    AvailabilityService.requiredSkillIds(svc), req.getEmployeeId());
        }
        String type = svc.getRequiredResourceType();
        if (type != null && !type.isBlank()) {
            // 选目标时排除本预约自身旧 TIME_SLOT；锁目标后再查其他预约，通过后才释放旧占用。
            Long resourceId = availability.pickFreeResource(
                    type, date, svc.getStoreId(), fromSlot, toSlot, apt.getId());
            if (resourceId == null) {
                throw new BusinessException(ResourceErrorCode.RESOURCE_FULL);
            }
            KennelResourceView locked = occupancyApi.lockResourceForUse(svc.getStoreId(), resourceId);
            if (locked == null || !"AVAILABLE".equals(locked.getStatus())) {
                throw new BusinessException(ResourceErrorCode.RESOURCE_FULL);
            }
            if (occupancyApi.hasOtherTimeSlotOccupancyForUpdate(
                    resourceId, date, fromSlot, toSlot, apt.getId())) {
                throw new BusinessException(ResourceErrorCode.RESOURCE_FULL);
            }
            occupancyApi.releaseAll(apt.getId());
            List<Integer> slots = new ArrayList<>();
            for (int s = fromSlot; s < toSlot; s++) {
                slots.add(s);
            }
            occupancyApi.occupyTimeSlots(resourceId, apt.getId(), date, slots);
        } else {
            // 无资源依赖的服务仍需沿用原有“释放旧占用”语义。
            occupancyApi.releaseAll(apt.getId());
        }
        if (appointmentMapper.updateModifyInstant(apt.getId(), apt.getUserId(), date, start, end,
                req.getEmployeeId(), expireAt) == 0) {
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION);
        }
    }
}
