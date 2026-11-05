package com.petplatform.schedule.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.schedule.api.ResourceErrorCode;
import com.petplatform.schedule.api.ResourceOccupancyApi;
import com.petplatform.schedule.api.dto.KennelCapacityView;
import com.petplatform.schedule.api.dto.KennelResourceView;
import com.petplatform.schedule.api.dto.OccupancyView;
import com.petplatform.schedule.mapper.ResourceOccupancyMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 笼位占用/迁移/释放与容量（ADR-015）。迁移=同事务删旧插新（P2 语义），
 * 撞 UNIQUE(resource_id, work_date, slot_no) → 72001，由调用方事务整体回滚。
 */
@Service
public class ResourceOccupancyService implements ResourceOccupancyApi {

    private final ResourceOccupancyMapper mapper;

    public ResourceOccupancyService(ResourceOccupancyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public KennelResourceView resource(Long storeId, Long resourceId) {
        if (storeId == null || resourceId == null) {
            return null;
        }
        return mapper.selectResource(storeId, resourceId);
    }

    /**
     * 资源行锁必须加入调用方已有事务（REQUIRED）。当前调用点：预约创建/改期事务段、托管入住事务，
     * 均在写入占用之前调用；锁随外层事务提交/回滚释放。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public KennelResourceView lockResourceForUse(Long storeId, Long resourceId) {
        if (storeId == null || resourceId == null) {
            return null;
        }
        return mapper.selectResourceForUpdate(storeId, resourceId);
    }

    @Override
    public List<KennelCapacityView> kennelCapacity(Long storeId, LocalDate date, String type) {
        Map<String, KennelCapacityView> byType = mapper.selectKennelTotals(storeId, type).stream()
                .collect(Collectors.toMap(KennelCapacityView::getType, Function.identity(), (a, b) -> a));
        for (KennelCapacityView occ : mapper.selectKennelOccupied(storeId, date, type)) {
            KennelCapacityView t = byType.get(occ.getType());
            if (t != null) {
                t.setOccupied(occ.getOccupied());
            }
        }
        return byType.values().stream()
                .peek(v -> v.setAvailable(Math.max(0, v.getTotal() - v.getOccupied())))
                .sorted((a, b) -> a.getType().compareTo(b.getType()))
                .toList();
    }

    @Override
    public List<OccupancyView> occupancyOfAppointment(Long appointmentId) {
        return appointmentId == null ? List.of() : mapper.selectOccupancy(appointmentId);
    }

    @Override
    public Map<Long, Set<Integer>> timeSlotOccupancyOfAppointment(Long appointmentId, LocalDate date) {
        Map<Long, Set<Integer>> byResource = new HashMap<>();
        if (appointmentId == null || date == null) {
            return byResource;
        }
        for (ResourceOccupancyMapper.SlotOccupyRow row
                : mapper.selectTimeSlotOccupancyOfAppointment(appointmentId, date)) {
            byResource.computeIfAbsent(row.getResourceId(), k -> new HashSet<>()).add(row.getSlotNo());
        }
        return byResource;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean hasOtherTimeSlotOccupancyForUpdate(Long resourceId, LocalDate date, int fromSlot,
                                                      int toSlotExclusive, Long excludeAppointmentId) {
        if (resourceId == null || date == null || excludeAppointmentId == null
                || fromSlot < 0 || toSlotExclusive > 48 || fromSlot >= toSlotExclusive) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "改期时段占用复检参数非法");
        }
        return mapper.selectOtherTimeSlotOccupancyForUpdate(
                resourceId, date, fromSlot, toSlotExclusive, excludeAppointmentId) != null;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean hasOtherDayOccupancyForUpdate(Long resourceId, LocalDate from, LocalDate toExclusive,
                                                 Long excludeAppointmentId) {
        if (resourceId == null || from == null || toExclusive == null || excludeAppointmentId == null
                || !from.isBefore(toExclusive)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "改期日期占用复检参数非法");
        }
        return mapper.selectOtherDayOccupancyForUpdate(
                resourceId, from, toExclusive, excludeAppointmentId) != null;
    }

    @Override
    @Transactional
    public void confirmOrMoveKennel(Long resourceId, Long appointmentId, LocalDate from, LocalDate toExclusive) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, toExclusive);
        if (resourceId == null || appointmentId == null || days <= 0) {
            throw new BusinessException(ResourceErrorCode.KENNEL_CAPACITY_INSUFFICIENT, "笼位占用区间非法");
        }
        List<OccupancyView> inRange = mapper.selectOccupancy(appointmentId).stream()
                .filter(o -> !o.getWorkDate().isBefore(from) && o.getWorkDate().isBefore(toExclusive))
                .toList();
        boolean alreadyOnTarget = inRange.size() == days
                && inRange.stream().allMatch(o -> resourceId.equals(o.getResourceId()));
        if (alreadyOnTarget) {
            return;
        }
        mapper.deleteOccupancyInRange(appointmentId, from, toExclusive);
        for (LocalDate d = from; d.isBefore(toExclusive); d = d.plusDays(1)) {
            try {
                mapper.insertDayOccupancy(IdWorker.getId(), resourceId, d, appointmentId);
            } catch (DuplicateKeyException e) {
                // 目标笼位该日已被他人占用：迁移冲突（调用方事务回滚，旧占用不丢）
                throw BusinessException.of(ResourceErrorCode.KENNEL_MOVE_CONFLICT);
            }
        }
    }

    @Override
    @Transactional
    public int releaseOccupancy(Long appointmentId, LocalDate from, LocalDate toExclusive) {
        if (appointmentId == null || from == null || toExclusive == null || !from.isBefore(toExclusive)) {
            return 0;
        }
        return mapper.deleteOccupancyInRange(appointmentId, from, toExclusive);
    }

    @Override
    @Transactional
    public int releaseAll(Long appointmentId) {
        return appointmentId == null ? 0 : mapper.deleteAllOfAppointment(appointmentId);
    }

    @Override
    public List<KennelResourceView> availableResources(Long storeId, String type) {
        if (storeId == null || type == null || type.isBlank()) {
            return List.of();
        }
        return mapper.selectAvailableResources(storeId, type);
    }

    @Override
    public Map<Long, Set<Integer>> daySlotOccupancy(Long storeId, String type, LocalDate date) {
        Map<Long, Set<Integer>> byResource = new java.util.HashMap<>();
        for (ResourceOccupancyMapper.SlotOccupyRow row : mapper.selectDaySlotOccupancy(storeId, type, date)) {
            byResource.computeIfAbsent(row.getResourceId(), k -> new java.util.HashSet<>()).add(row.getSlotNo());
        }
        return byResource;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void occupyTimeSlots(Long resourceId, Long appointmentId, LocalDate date, List<Integer> slotNos) {
        try {
            for (Integer slot : slotNos) {
                mapper.insertSlotOccupancy(IdWorker.getId(), resourceId, date, slot, appointmentId);
            }
        } catch (DuplicateKeyException e) {
            // 唯一索引 = 防超卖最后防线（ADR-005-2）：友好映射"重选时段"，调用方事务回滚
            throw BusinessException.of(ResourceErrorCode.SLOT_TAKEN);
        }
    }

    @Override
    public Set<Long> kennelIdsOccupiedBetween(Long storeId, LocalDate from, LocalDate toExclusive) {
        if (storeId == null || from == null || !from.isBefore(toExclusive)) {
            return Set.of();
        }
        return mapper.selectDayOccupiedResourceIds(storeId, from, toExclusive);
    }
}
