package com.petplatform.pet.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.appointment.api.AppointmentQueryApi;
import com.petplatform.appointment.api.dto.LastAppointmentView;
import com.petplatform.boarding.api.BoardingQueryApi;
import com.petplatform.boarding.api.dto.BoardingStayView;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.result.PageResult;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.fulfillment.api.ServiceRecordQueryApi;
import com.petplatform.fulfillment.api.dto.LastServiceView;
import com.petplatform.pet.api.PetErrorCode;
import com.petplatform.pet.api.dto.PetHealthVO;
import com.petplatform.pet.api.dto.PetVO;
import com.petplatform.pet.api.dto.StorePetDetailVO;
import com.petplatform.pet.api.dto.StorePetItemVO;
import com.petplatform.pet.api.dto.StorePetOverviewVO;
import com.petplatform.pet.api.dto.StorePetQuery;
import com.petplatform.pet.domain.PetEntity;
import com.petplatform.pet.domain.PetHealthEntity;
import com.petplatform.pet.mapper.PetHealthMapper;
import com.petplatform.pet.mapper.PetMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * B 端宠物只读视图（ADR-014）。可见范围以权限码判定（不查角色表，避免双源）：
 * - 持 appointment:view:store（店长/前台）→ 店范围 = 本店预约关联宠物 ∪ 本店在店托管宠物；
 * - 任务角色（医生/美容/遛狗/护理）→ 任务范围 = 本店分配给我的宠物 ∪ 我在店的托管宠物；
 * - 跨店伪造由 PermissionAssembler 空权限 → 10003 兜底（本服务只认已校验的 storeCtx）。
 * 聚合全部经对方模块只读 api，禁止直查预约／托管／履约业务表。
 */
@Slf4j
@Service
public class StorePetQueryService {

    static final String CODE_STORE_WIDE = "appointment:view:store";
    static final String CODE_HEALTH_VIEW = "pet:health:view";

    private final PetMapper petMapper;
    private final PetHealthMapper healthMapper;
    private final AppointmentQueryApi appointmentQueryApi;
    private final BoardingQueryApi boardingQueryApi;
    private final ServiceRecordQueryApi serviceRecordQueryApi;
    private final ObjectMapper json;
    private final PetAvatarSigner avatarSigner;

    public StorePetQueryService(PetMapper petMapper, PetHealthMapper healthMapper,
                                AppointmentQueryApi appointmentQueryApi, BoardingQueryApi boardingQueryApi,
                                ServiceRecordQueryApi serviceRecordQueryApi, ObjectMapper json,
                                PetAvatarSigner avatarSigner) {
        this.petMapper = petMapper;
        this.healthMapper = healthMapper;
        this.appointmentQueryApi = appointmentQueryApi;
        this.boardingQueryApi = boardingQueryApi;
        this.serviceRecordQueryApi = serviceRecordQueryApi;
        this.json = json;
        this.avatarSigner = avatarSigner;
    }

    public StorePetOverviewVO overview(Long storeId, AuthPrincipal principal) {
        Set<Long> scope = scopePetIds(storeId, principal);
        long total = 0;
        long dogs = 0;
        long cats = 0;
        if (!scope.isEmpty()) {
            List<PetEntity> pets = petMapper.selectList(Wrappers.<PetEntity>lambdaQuery()
                    .select(PetEntity::getSpecies).in(PetEntity::getId, scope));
            total = pets.size();
            dogs = pets.stream().filter(p -> "DOG".equals(p.getSpecies())).count();
            cats = pets.stream().filter(p -> "CAT".equals(p.getSpecies())).count();
        }
        return StorePetOverviewVO.builder()
                .totalPets(total).dogCount(dogs).catCount(cats)
                .inBoardingCount(boardingQueryApi.countInStore(storeId))
                .build();
    }

    public PageResult<StorePetItemVO> page(Long storeId, AuthPrincipal principal, StorePetQuery query) {
        Set<Long> scope = scopePetIds(storeId, principal);
        if (scope.isEmpty()) {
            return PageResult.of(0, query.getPageNum(), query.getPageSize(), List.of());
        }
        Page<PetEntity> mpPage = petMapper.selectPage(
                Page.of(query.getPageNum(), query.getPageSize()),
                Wrappers.<PetEntity>lambdaQuery()
                        .in(PetEntity::getId, scope)
                        .eq(notBlank(query.getSpecies()), PetEntity::getSpecies, query.getSpecies())
                        .eq(notBlank(query.getStatus()), PetEntity::getStatus, query.getStatus())
                        .and(notBlank(query.getKeyword()), w -> w
                                .like(PetEntity::getName, query.getKeyword())
                                .or().like(PetEntity::getBreed, query.getKeyword()))
                        .orderByDesc(PetEntity::getUpdatedAt)
                        .orderByDesc(PetEntity::getId));
        List<StorePetItemVO> items = assemble(storeId, mpPage.getRecords());
        return PageResult.of(mpPage.getTotal(), query.getPageNum(), query.getPageSize(), items);
    }

    /** 供其他聚合读口复用的可见性闸：不可见/不存在/已删统一 20101。 */
    public void requireVisible(Long storeId, AuthPrincipal principal, Long petId) {
        requireVisiblePet(storeId, principal, petId);
    }

    public StorePetDetailVO detail(Long storeId, AuthPrincipal principal, Long petId) {
        PetEntity pet = requireVisiblePet(storeId, principal, petId);
        StorePetItemVO item = assemble(storeId, List.of(pet)).get(0);
        PetHealthEntity health = principal.permissions().contains(CODE_HEALTH_VIEW)
                ? healthMapper.selectOne(Wrappers.<PetHealthEntity>lambdaQuery()
                        .eq(PetHealthEntity::getPetId, petId))
                : null;
        return StorePetDetailVO.builder()
                .pet(toVo(pet))
                .healthSummary(item.getHealth())
                .lastService(item.getLastService())
                .boarding(item.getBoarding())
                .health(health == null ? null : PetHealthVO.builder()
                        .petId(petId)
                        .allergies(readList(health.getAllergies()))
                        .chronicDiseases(readList(health.getChronicDiseases()))
                        .specialNotes(health.getSpecialNotes())
                        .updatedByRole(health.getUpdatedByRole())
                        .updatedAt(health.getUpdatedAt())
                        .build())
                .build();
    }

    private PetEntity requireVisiblePet(Long storeId, AuthPrincipal principal, Long petId) {
        if (!scopePetIds(storeId, principal).contains(petId)) {
            // 不可见与不存在同码：不回显存在性（ADR-014 §2/§3）
            throw BusinessException.of(PetErrorCode.PET_FORBIDDEN);
        }
        PetEntity pet = petMapper.selectById(petId);
        if (pet == null) {
            throw BusinessException.of(PetErrorCode.PET_FORBIDDEN);
        }
        return pet;
    }

    /** 包内可见：范围解析供单测与 detail/page 共用。 */
    Set<Long> scopePetIds(Long storeId, AuthPrincipal principal) {
        Set<Long> scope = new HashSet<>();
        if (principal.permissions().contains(CODE_STORE_WIDE)) {
            scope.addAll(appointmentQueryApi.petIdsInStore(storeId));
            scope.addAll(boardingQueryApi.inStorePetIds(storeId));
            // ADR-058 增量（V9）：本店代建的宠物即便尚无预约/托管，本店也应可见
            scope.addAll(petMapper.selectIdsByOriginStore(storeId));
        } else {
            Long employeeId = principal.employeeId();
            scope.addAll(appointmentQueryApi.assignedPetIds(storeId, employeeId));
            scope.addAll(boardingQueryApi.caretakerPetIds(storeId, employeeId));
        }
        return scope;
    }

    private List<StorePetItemVO> assemble(Long storeId, List<PetEntity> pets) {
        if (pets.isEmpty()) {
            return List.of();
        }
        List<Long> petIds = pets.stream().map(PetEntity::getId).toList();
        Map<Long, PetHealthEntity> healths = healthMapper.selectList(Wrappers.<PetHealthEntity>lambdaQuery()
                        .in(PetHealthEntity::getPetId, petIds)).stream()
                .collect(Collectors.toMap(PetHealthEntity::getPetId, Function.identity(), (a, b) -> a));
        // 履约表没有 store_id：先取本店预约 ID，再在该集合内取每只宠物的最新履约；
        // 不能用 lastServices(petIds) 的全局结果，否则卡片会显示其他门店的最近服务。
        Set<Long> storeAppointmentIds = appointmentQueryApi.appointmentIdsInStore(storeId, petIds);
        Map<Long, LastServiceView> services = storeAppointmentIds.isEmpty() ? Map.of()
                : serviceRecordQueryApi.lastServicesInAppointments(petIds, storeAppointmentIds).stream()
                .collect(Collectors.toMap(LastServiceView::getPetId, Function.identity(), (a, b) -> a));
        Map<Long, LastAppointmentView> appointments = appointmentQueryApi.lastAppointments(storeId, petIds).stream()
                .collect(Collectors.toMap(LastAppointmentView::getPetId, Function.identity(), (a, b) -> a));
        Map<Long, BoardingStayView> stays = boardingQueryApi.currentStays(petIds).stream()
                .collect(Collectors.toMap(BoardingStayView::getPetId, Function.identity(), (a, b) -> a));
        Map<Long, String> avatarUrls = avatarSigner.signAll(pets.stream()
                .filter(p -> p.getAvatar() != null)
                .collect(Collectors.toMap(PetEntity::getId, PetEntity::getAvatar, (a, b) -> a)));

        return pets.stream().map(p -> StorePetItemVO.builder()
                .id(p.getId()).name(p.getName()).species(p.getSpecies()).breed(p.getBreed())
                .gender(p.getGender()).birthday(p.getBirthday()).birthdayApprox(p.getBirthdayApprox())
                .weightKg(p.getWeightKg()).sizeCategory(p.getSizeCategory()).avatar(p.getAvatar())
                .avatarUrl(avatarUrls.get(p.getId()))
                .status(p.getStatus())
                .health(healthSummary(healths.get(p.getId()), p.getVaccineSummary()))
                .lastService(lastService(services.get(p.getId()), appointments.get(p.getId())))
                .boarding(boarding(stays.get(p.getId())))
                .build()).toList();
    }

    private StorePetItemVO.HealthSummaryVO healthSummary(PetHealthEntity h, String vaccineSummary) {
        return StorePetItemVO.HealthSummaryVO.builder()
                .allergyCount(h == null ? 0 : readList(h.getAllergies()).size())
                .chronicCount(h == null ? 0 : readList(h.getChronicDiseases()).size())
                .hasSpecialNotes(h != null && h.getSpecialNotes() != null && !h.getSpecialNotes().isBlank())
                .vaccineSummary(vaccineSummary)
                .build();
    }

    /** 履约记录优先，预约快照兜底（ADR-014 聚合降级）。 */
    private StorePetItemVO.LastServiceVO lastService(LastServiceView svc, LastAppointmentView appt) {
        if (svc != null) {
            return StorePetItemVO.LastServiceVO.builder()
                    .source("SERVICE").appointmentId(svc.getAppointmentId())
                    .label(svc.getType()).finishedAt(svc.getFinishedAt())
                    .build();
        }
        if (appt != null) {
            return StorePetItemVO.LastServiceVO.builder()
                    .source("APPOINTMENT").appointmentId(appt.getAppointmentId())
                    .label(appt.getServiceName()).serviceDate(appt.getServiceDate())
                    .status(appt.getStatus())
                    .build();
        }
        return null;
    }

    private StorePetItemVO.BoardingBriefVO boarding(BoardingStayView stay) {
        if (stay == null) {
            return null;
        }
        return StorePetItemVO.BoardingBriefVO.builder()
                .stayId(stay.getId()).status(stay.getStatus())
                .checkinDate(stay.getCheckinDate()).checkoutDate(stay.getCheckoutDate())
                .build();
    }

    private PetVO toVo(PetEntity p) {
        return PetVO.builder()
                .id(p.getId()).name(p.getName()).species(p.getSpecies()).breed(p.getBreed())
                .gender(p.getGender()).isNeutered(p.getIsNeutered()).birthday(p.getBirthday())
                .birthdayApprox(p.getBirthdayApprox()).weightKg(p.getWeightKg())
                .sizeCategory(p.getSizeCategory()).avatar(p.getAvatar()).remark(p.getRemark())
                .avatarUrl(avatarSigner.sign(p.getId(), p.getAvatar()))
                .vaccineSummary(p.getVaccineSummary()).status(p.getStatus()).createdAt(p.getCreatedAt())
                .build();
    }

    private List<String> readList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("健康档案 JSON 解析失败降级空数组: {}", e.getMessage());
            return List.of();
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
