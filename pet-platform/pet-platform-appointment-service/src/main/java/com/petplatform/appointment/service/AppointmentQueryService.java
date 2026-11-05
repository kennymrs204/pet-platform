package com.petplatform.appointment.service;

import com.petplatform.appointment.api.AppointmentErrorCode;
import com.petplatform.appointment.api.AppointmentQueryApi;
import com.petplatform.appointment.api.dto.AppointmentStoreQuery;
import com.petplatform.appointment.api.dto.AppointmentStoreView;
import com.petplatform.appointment.api.dto.BoardingAppointmentView;
import com.petplatform.appointment.api.dto.CustomerAggView;
import com.petplatform.appointment.api.dto.CustomerVisitView;
import com.petplatform.appointment.api.dto.LastAppointmentView;
import com.petplatform.appointment.api.dto.PetServiceVisitView;
import com.petplatform.appointment.mapper.AppointmentQueryMapper;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.result.PageResult;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@Service
public class AppointmentQueryService implements AppointmentQueryApi {

    private final AppointmentQueryMapper mapper;
    private final com.petplatform.appointment.mapper.AppointmentCommandMapper commandMapper;
    private final org.springframework.context.ApplicationEventPublisher events;

    public AppointmentQueryService(AppointmentQueryMapper mapper,
                                   com.petplatform.appointment.mapper.AppointmentCommandMapper commandMapper,
                                   org.springframework.context.ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.commandMapper = commandMapper;
        this.events = events;
    }

    @Override
    public Set<Long> petIdsInStore(Long storeId) {
        return storeId == null ? Set.of() : mapper.selectPetIdsInStore(storeId);
    }

    @Override
    public Set<Long> assignedPetIds(Long storeId, Long employeeId) {
        if (storeId == null || employeeId == null || employeeId == 0L) {
            return Set.of();
        }
        return mapper.selectAssignedPetIds(storeId, employeeId);
    }

    @Override
    public long countScheduleOccupying(Long storeId, Long employeeId, java.time.LocalDate workDate) {
        if (storeId == null || employeeId == null || employeeId == 0L || workDate == null) {
            return 0L;
        }
        return mapper.countScheduleOccupying(storeId, employeeId, workDate);
    }

    @Override
    public Set<Long> appointmentIdsInStore(Long storeId, Collection<Long> petIds) {
        if (storeId == null || storeId == 0L || petIds == null || petIds.isEmpty()) {
            return Set.of();
        }
        return mapper.selectAppointmentIdsInStore(storeId, petIds);
    }

    @Override
    public List<LastAppointmentView> lastAppointments(Long storeId, Collection<Long> petIds) {
        if (storeId == null || petIds == null || petIds.isEmpty()) {
            return List.of();
        }
        return mapper.selectLastAppointments(storeId, petIds).stream()
                .map(r -> LastAppointmentView.builder()
                        .petId(r.getPetId()).appointmentId(r.getAppointmentId())
                        .serviceName(r.getServiceName()).serviceDate(r.getServiceDate())
                        .status(r.getStatus())
                        .build())
                .toList();
    }

    @Override
    public PageResult<PetServiceVisitView> petServiceHistory(Long storeId, Long petId, Long employeeId,
                                                              int pageNum, int pageSize) {
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), 100);
        if (storeId == null || storeId == 0L || petId == null) {
            return PageResult.of(0, page, size, List.of());
        }
        long total = mapper.countPetServiceHistory(storeId, petId, employeeId);
        if (total == 0) {
            return PageResult.of(0, page, size, List.of());
        }
        List<PetServiceVisitView> list = mapper.selectPetServiceHistory(
                        storeId, petId, employeeId, (long) (page - 1) * size, size)
                .stream()
                .map(r -> PetServiceVisitView.builder()
                        .appointmentId(r.getId())
                        .appointmentNo(r.getAppointmentNo())
                        .petId(r.getPetId())
                        .serviceDate(r.getServiceDate())
                        .startTime(r.getStartTime())
                        .endTime(r.getEndTime())
                        .serviceName(r.getServiceName())
                        .status(r.getStatus())
                        .priceSnapshot(r.getPriceSnapshot())
                        .employeeId(r.getEmployeeId())
                        .boardingCheckinDate(r.getBoardingCheckinDate())
                        .boardingCheckoutDate(r.getBoardingCheckoutDate())
                        .actualEndAt(r.getActualEndAt())
                        .build())
                .toList();
        return PageResult.of(total, page, size, list);
    }

    @Override
    public boolean hasBlockingByPet(Long petId) {
        return petId != null && mapper.existsBlockingByPet(petId) > 0;
    }

    @Override
    public BoardingAppointmentView boardingSnapshot(Long appointmentId) {
        if (appointmentId == null) {
            return null;
        }
        AppointmentQueryMapper.BoardingSnapshotRow r = mapper.selectBoardingSnapshot(appointmentId);
        if (r == null) {
            return null;
        }
        return BoardingAppointmentView.builder()
                .appointmentId(r.getId()).storeId(r.getStoreId()).petId(r.getPetId())
                .status(r.getStatus()).checkinDate(r.getBoardingCheckinDate())
                .checkoutDate(r.getBoardingCheckoutDate()).serviceNameSnapshot(r.getServiceNameSnapshot())
                .build();
    }

    @Override
    public java.util.Map<Long, String> boardingStatuses(Collection<Long> appointmentIds) {
        if (appointmentIds == null || appointmentIds.isEmpty()) {
            return java.util.Map.of();
        }
        return commandMapper.selectBatchIds(appointmentIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.petplatform.appointment.domain.AppointmentEntity::getId,
                        com.petplatform.appointment.domain.AppointmentEntity::getStatus,
                        (a, b) -> a));
    }

    @Override
    public List<Long> boardingAppointmentIdsOfUser(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return mapper.selectBoardingAppointmentIdsOfUser(userId);
    }

    @Override
    public Long ownerIdOfAppointment(Long appointmentId) {
        if (appointmentId == null) {
            return null;
        }
        var apt = commandMapper.selectById(appointmentId);
        return apt == null ? null : apt.getUserId();
    }

    @Override
    public void startBoarding(Long appointmentId) {
        if (mapper.updateStartBoarding(appointmentId) == 0) {
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION, "预约当前状态不可办理入住");
        }
    }

    @Override
    public void completeBoarding(Long appointmentId) {
        if (mapper.updateCompleteBoarding(appointmentId) == 0) {
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION, "预约当前状态不可办理离店");
        }
        // ADR-053：托管离店把预约落到 COMPLETED（唯一的托管完成入口）→ AFTER_COMMIT 给顾客发"已办理离店"。
        // 事实取自落库后的一手行；CAS==0 已在上方拒绝，幂等重放不会重复发。
        var apt = commandMapper.selectById(appointmentId);
        events.publishEvent(new com.petplatform.appointment.api.event.AppointmentCompletedEvent(
                appointmentId, apt == null ? null : apt.getUserId(), apt == null ? null : apt.getStoreId(),
                apt == null ? null : apt.getPetId(),
                apt == null ? null : apt.getServiceNameSnapshot(),
                com.petplatform.appointment.api.event.AppointmentCompletedEvent.SOURCE_BOARDING_CHECKOUT,
                apt == null ? null : apt.getActualStartAt(),
                apt == null ? null : apt.getActualEndAt(),
                java.time.LocalDateTime.now()));
    }

    @Override
    public com.petplatform.common.result.PageResult<com.petplatform.appointment.api.dto.AppointmentSelfView>
    selfPage(Long userId, String status, int pageNum, int pageSize) {
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), 100);
        var mpPage = commandMapper.selectPage(
                com.baomidou.mybatisplus.extension.plugins.pagination.Page.of(page, size),
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.petplatform.appointment.domain.AppointmentEntity>lambdaQuery()
                        .eq(com.petplatform.appointment.domain.AppointmentEntity::getUserId, userId)
                        .eq(status != null && !status.isBlank(),
                                com.petplatform.appointment.domain.AppointmentEntity::getStatus, status)
                        .orderByDesc(com.petplatform.appointment.domain.AppointmentEntity::getCreatedAt)
                        .orderByDesc(com.petplatform.appointment.domain.AppointmentEntity::getId));
        return com.petplatform.common.result.PageResult.of(mpPage.getTotal(), page, size,
                mpPage.getRecords().stream().map(this::toSelfView).toList());
    }

    @Override
    public com.petplatform.appointment.api.dto.AppointmentSelfView selfDetail(Long userId, Long appointmentId) {
        var apt = appointmentId == null ? null : commandMapper.selectById(appointmentId);
        if (apt == null || !java.util.Objects.equals(apt.getUserId(), userId)) {
            return null; // 非本人=不存在语义（10004 由调用方处理）
        }
        return toSelfView(apt);
    }

    @Override
    public java.util.List<com.petplatform.appointment.api.dto.TimeoutAppointmentView>
    scanTimeoutAppointments(java.time.LocalDateTime now, int limit) {
        return commandMapper.scanTimeout(now, Math.min(Math.max(limit, 1), 200));
    }

    private com.petplatform.appointment.api.dto.AppointmentSelfView toSelfView(
            com.petplatform.appointment.domain.AppointmentEntity a) {
        return com.petplatform.appointment.api.dto.AppointmentSelfView.builder()
                .id(a.getId()).appointmentNo(a.getAppointmentNo()).petId(a.getPetId())
                .storeId(a.getStoreId()).storeServiceId(a.getStoreServiceId()).employeeId(a.getEmployeeId())
                .serviceDate(a.getServiceDate()).startTime(a.getStartTime()).endTime(a.getEndTime())
                .boardingCheckinDate(a.getBoardingCheckinDate()).boardingCheckoutDate(a.getBoardingCheckoutDate())
                .status(a.getStatus()).serviceNameSnapshot(a.getServiceNameSnapshot())
                .priceSnapshot(a.getPriceSnapshot()).durationSnapshot(a.getDurationSnapshot())
                .remark(a.getRemark()).expireAt(a.getExpireAt()).createdAt(a.getCreatedAt())
                .build();
    }

    @Override
    public PageResult<AppointmentStoreView> storeAppointments(AppointmentStoreQuery query) {
        int pageNum = Math.max(query.getPageNum(), 1);
        int pageSize = Math.min(Math.max(query.getPageSize(), 1), AppointmentStoreQuery.MAX_PAGE_SIZE);
        long total = mapper.countStore(query.getStoreId(), query.effectiveDate(), query.getStatuses(), query.getEmployeeId());
        if (total == 0) {
            return PageResult.of(0, pageNum, pageSize, List.of());
        }
        List<AppointmentStoreView> list = mapper
                .selectStorePage(query.getStoreId(), query.effectiveDate(), query.getStatuses(), query.getEmployeeId(),
                        (long) (pageNum - 1) * pageSize, pageSize)
                .stream()
                .map(r -> AppointmentStoreView.builder()
                        .id(r.getId())
                        .appointmentNo(r.getAppointmentNo())
                        .serviceDate(r.getServiceDate())
                        .startTime(r.getStartTime())
                        .endTime(r.getEndTime())
                        .status(r.getStatus())
                        .serviceName(r.getServiceName())
                        .priceSnapshot(r.getPriceSnapshot())
                        .source(r.getSource())
                        .employeeId(r.getEmployeeId())
                        .petId(r.getPetId())
                        .storeServiceId(r.getStoreServiceId())
                        .build())
                .toList();
        return PageResult.of(total, pageNum, pageSize, list);
    }

    @Override
    public List<CustomerAggView> customerAggInStore(Long storeId) {
        if (storeId == null || storeId == 0L) {
            return List.of();
        }
        return mapper.selectCustomerAggInStore(storeId).stream()
                .map(r -> CustomerAggView.builder()
                        .userId(r.getUserId())
                        .firstVisitDate(r.getFirstVisitDate())
                        .lastVisitDate(r.getLastVisitDate())
                        .visitCount(r.getVisitCount())
                        .hasValidAppointment(r.getValidCount() > 0)
                        .build())
                .toList();
    }

    @Override
    public List<CustomerVisitView> visitsOfCustomer(Long storeId, Long userId, int limit, int offset) {
        if (storeId == null || storeId == 0L || userId == null) {
            return List.of();
        }
        int capped = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);
        return mapper.selectVisitsOfCustomer(storeId, userId, capped, safeOffset).stream()
                .map(r -> CustomerVisitView.builder()
                        .appointmentId(r.getId())
                        .serviceDate(r.getServiceDate())
                        .serviceName(r.getServiceName())
                        .status(r.getStatus())
                        .priceSnapshot(r.getPriceSnapshot())
                        .employeeId(r.getEmployeeId())
                        .petId(r.getPetId())
                        .build())
                .toList();
    }
}
