package com.petplatform.pet.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.appointment.api.AppointmentQueryApi;
import com.petplatform.boarding.api.BoardingQueryApi;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.pet.api.PetErrorCode;
import com.petplatform.pet.api.dto.PetBaseUpdateRequest;
import com.petplatform.pet.api.dto.PetCreateRequest;
import com.petplatform.pet.api.dto.PetHealthActor;
import com.petplatform.pet.api.dto.PetHealthSnapshotView;
import com.petplatform.pet.api.dto.PetHealthUpdateRequest;
import com.petplatform.pet.api.dto.PetHealthVO;
import com.petplatform.pet.api.dto.PetUpdateRequest;
import com.petplatform.pet.api.dto.PetVO;
import com.petplatform.pet.api.event.PetCreatedEvent;
import com.petplatform.pet.domain.PetEntity;
import com.petplatform.pet.domain.PetHealthChangeLogEntity;
import com.petplatform.pet.domain.PetHealthEntity;
import com.petplatform.pet.mapper.PetHealthChangeLogMapper;
import com.petplatform.pet.mapper.PetHealthMapper;
import com.petplatform.pet.mapper.PetMapper;
import com.petplatform.user.api.UserAccountApi;
import com.petplatform.user.api.UserPetApi;
import com.petplatform.user.api.dto.UserProfileView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 宠物档案写侧（ADR-014）：C 端 CRUD/健康档案 + B 端受保护的健康写入与删除。
 * C 端归属校验一律经 UserPetApi（usr_user_pet Owner=user 模块）；
 * 非归属/不存在/已删统一 20101，不回显存在性（防枚举）。
 */
@Slf4j
@Service
public class PetService {

    private final PetMapper petMapper;
    private final PetHealthMapper healthMapper;
    private final PetHealthChangeLogMapper healthChangeLogMapper;
    private final UserPetApi userPetApi;
    private final UserAccountApi userAccountApi;
    private final AppointmentQueryApi appointmentQueryApi;
    private final BoardingQueryApi boardingQueryApi;
    private final SizeCategoryResolver sizeCategoryResolver;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final org.springframework.beans.factory.ObjectProvider<com.petplatform.common.storage.MediaStorage> media;
    private final long maxAvatarBytes;

    public PetService(PetMapper petMapper, PetHealthMapper healthMapper,
                      PetHealthChangeLogMapper healthChangeLogMapper,
                      UserPetApi userPetApi, UserAccountApi userAccountApi,
                      AppointmentQueryApi appointmentQueryApi, BoardingQueryApi boardingQueryApi,
                      SizeCategoryResolver sizeCategoryResolver, ApplicationEventPublisher events,
                      ObjectMapper json,
                      org.springframework.beans.factory.ObjectProvider<com.petplatform.common.storage.MediaStorage> media,
                      @org.springframework.beans.factory.annotation.Value("${pet.storage.max-size-mb:5}") long maxSizeMb) {
        this.petMapper = petMapper;
        this.healthMapper = healthMapper;
        this.healthChangeLogMapper = healthChangeLogMapper;
        this.userPetApi = userPetApi;
        this.userAccountApi = userAccountApi;
        this.appointmentQueryApi = appointmentQueryApi;
        this.boardingQueryApi = boardingQueryApi;
        this.sizeCategoryResolver = sizeCategoryResolver;
        this.events = events;
        this.json = json;
        this.media = media;
        this.maxAvatarBytes = maxSizeMb * 1024 * 1024;
    }

    public List<PetVO> listMine(Long userId) {
        List<Long> ids = userPetApi.manageablePetIds(userId);
        if (ids.isEmpty()) {
            return List.of();
        }
        return petMapper.selectList(Wrappers.<PetEntity>lambdaQuery()
                        .in(PetEntity::getId, ids)
                        .orderByDesc(PetEntity::getCreatedAt)
                        .orderByDesc(PetEntity::getId))
                .stream().map(pet -> toVo(pet, userId)).toList();
    }

    /**
     * 创建宠物档案。{@code originStoreId} = 代建门店（ADR-058 增量）：
     * C 端自建传 null；B 端门店代建传该店 id，作为"本店可见范围"的第三来源。
     */
    @Transactional
    public PetVO create(Long userId, Long originStoreId, PetCreateRequest req) {
        validateAvatar(userId, req.getAvatar());
        PetEntity pet = new PetEntity();
        applyBasics(pet, req.getName(), req.getSpecies(), req.getBreed(), req.getGender(),
                req.getIsNeutered(), req.getBirthday(), req.getBirthdayApprox(),
                req.getWeightKg(), req.getAvatar(), req.getRemark());
        pet.setStatus("NORMAL");
        pet.setOriginStoreId(originStoreId);
        petMapper.insert(pet);

        PetHealthEntity health = new PetHealthEntity();
        health.setPetId(pet.getId());
        healthMapper.insert(health);

        userPetApi.grantOwner(userId, pet.getId());
        events.publishEvent(new PetCreatedEvent(pet.getId(), userId, java.time.LocalDateTime.now()));
        return toVo(petMapper.selectById(pet.getId()), userId);
    }

    public PetVO detail(Long userId, Long petId) {
        PetEntity pet = requireManageable(userId, petId);
        return toVo(pet, userId);
    }

    @Transactional
    public PetVO update(Long userId, Long petId, PetUpdateRequest req) {
        PetEntity pet = requireManageable(userId, petId);
        validateAvatar(userId, req.getAvatar());
        applyBasics(pet, req.getName(), req.getSpecies(), req.getBreed(), req.getGender(),
                req.getIsNeutered(), req.getBirthday(), req.getBirthdayApprox(),
                req.getWeightKg(), req.getAvatar(), req.getRemark());
        if (req.getStatus() != null && !req.getStatus().isBlank()) {
            pet.setStatus(req.getStatus());
        }
        petMapper.updateById(pet);
        return toVo(petMapper.selectById(petId), userId);
    }

    /**
     * B 端受限基础资料更新：只应用 PetBaseUpdateRequest 允许的字段。
     * 头像/status/originStoreId/vaccineSummary 保留加载到的原值；sizeCategory 按新体重重算。
     */
    @Transactional
    public PetVO updateBase(Long ownerUserId, Long petId, PetBaseUpdateRequest req) {
        PetEntity pet = requireManageable(ownerUserId, petId);
        pet.setName(req.getName().trim());
        pet.setSpecies(req.getSpecies());
        pet.setBreed(emptyToNull(req.getBreed()));
        if (req.getGender() != null && !req.getGender().isBlank()) {
            pet.setGender(req.getGender());
        }
        if (req.getIsNeutered() != null) {
            pet.setIsNeutered(req.getIsNeutered());
        }
        if (req.getBirthday() != null) {
            pet.setBirthday(req.getBirthday());
            if (req.getBirthdayApprox() != null) {
                pet.setBirthdayApprox(req.getBirthdayApprox());
            }
        } else {
            pet.setBirthday(null);
            pet.setBirthdayApprox(false);
        }
        pet.setWeightKg(req.getWeightKg());
        pet.setSizeCategory(req.getWeightKg() == null ? null : sizeCategoryResolver.resolve(req.getWeightKg()));
        pet.setRemark(emptyToNull(req.getRemark()));
        petMapper.updateById(pet);
        return toVo(petMapper.selectById(petId), ownerUserId);
    }

    /**
     * C 端逻辑删除宠物 + 健康档案 + 全部用户关联；保留完成历史。
     * 与 B 端共用同一套预约/托管阻断与锁顺序，只保留 C 端 owner 校验。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Long userId, Long petId) {
        requireManageable(userId, petId);
        deleteWithBusinessGuards(petId);
    }

    /**
     * B 端门店删除。门店可见性由 app 层先校验；本方法在同一事务内锁健康行 → 宠物行，
     * 再全局检查预约/托管阻断记录，两类都通过后才执行逻辑删除。
     *
     * <p>READ_COMMITTED 是有意的：删除先取宠物行锁，等并发建单提交后再做阻断查询；
     * RR 的旧快照可能看不到刚提交的预约/托管。建单路径也必须先锁同一宠物行（见
     * AppointmentCreateTxService / BoardingStayService）。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteForStore(Long petId) {
        deleteWithBusinessGuards(petId);
    }

    /** B/C 共享的唯一删除核心：锁序一致、阻断口径一致、历史保留一致。 */
    private void deleteWithBusinessGuards(Long petId) {
        lockHealthAndPet(petId);
        if (appointmentQueryApi.hasBlockingByPet(petId)) {
            throw new BusinessException(CommonErrorCode.ILLEGAL_STATE_TRANSITION,
                    "该宠物仍有进行中或未完成的预约，暂时无法删除");
        }
        if (boardingQueryApi.hasBlockingByPet(petId)) {
            throw new BusinessException(CommonErrorCode.ILLEGAL_STATE_TRANSITION,
                    "该宠物仍有进行中或未完成的托管，暂时无法删除");
        }
        deleteLocked(petId);
    }

    /** 锁顺序固定：健康行 → 宠物行；与健康更新一致，删除同样不反向加锁。 */
    private void lockHealthAndPet(Long petId) {
        // 删除先拿到健康锁时，健康更新随后只会看到已删健康行并冲突；
        // 更新先拿到锁时，删除等待其健康行+日志提交后再删除。
        healthMapper.selectByPetIdForUpdate(petId);
        if (petMapper.selectByIdForUpdate(petId) == null) {
            throw BusinessException.of(PetErrorCode.PET_FORBIDDEN);
        }
    }

    /** 只做逻辑删除与归属解绑：预约/履约/托管/健康变更日志均不级联删除。 */
    private void deleteLocked(Long petId) {
        petMapper.deleteById(petId);
        healthMapper.delete(Wrappers.<PetHealthEntity>lambdaQuery().eq(PetHealthEntity::getPetId, petId));
        userPetApi.removeAllRelations(petId);
    }

    public PetHealthVO getHealth(Long userId, Long petId) {
        requireManageable(userId, petId);
        return toHealthVo(findHealth(petId), petId);
    }

    @Transactional
    public PetHealthVO updateHealth(Long userId, Long petId, PetHealthUpdateRequest req) {
        requireManageable(userId, petId);
        PetHealthActor actor = PetHealthActor.builder()
                .operatorType("CUSTOMER")
                .operatorId(userId)
                .operatorName(customerName(userId))
                .operatorRole("CUSTOMER")
                .build();
        return applyHealthUpdate(petId, actor, req);
    }

    /**
     * B 端健康档案写入（门店可见性/归属由 app 层先校验）。这里再查一次宠物存在性，
     * 避免可见性校验后被并发删除仍落下健康行；日志与健康行在同一事务内。
     *
     * <p>B/C 端普通健康编辑都强制携带读取时的 updatedAt：在事务内先 SELECT ... FOR UPDATE
     * 锁住健康行，再比较版本；版本不一致时在写任何数据前抛冲突，绝不产生日志。
     */
    @Transactional
    public PetHealthVO updateHealthForActor(Long petId, PetHealthActor actor, PetHealthUpdateRequest req) {
        if (petMapper.selectById(petId) == null) {
            throw BusinessException.of(PetErrorCode.PET_FORBIDDEN);
        }
        if (actor == null || actor.getOperatorId() == null || isBlank(actor.getOperatorType())) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少健康档案操作人身份");
        }
        return applyHealthUpdate(petId, actor, req);
    }

    /**
     * 行锁必须先于版本比较，两个写者才会在同一把行锁上串行。
     * 存在于健康行时 expectedUpdatedAt 必填；仅目标尚无健康行（首建）允许为 null。
     */
    private PetHealthVO applyHealthUpdate(Long petId, PetHealthActor actor, PetHealthUpdateRequest req) {
        PetHealthEntity health = healthMapper.selectByPetIdForUpdate(petId);
        assertHealthVersion(health, req.getExpectedUpdatedAt());
        // 锁顺序固定：健康行 → 宠物行。删除同样先锁健康行再删宠物；
        // 这样删除已提交后，本事务即使已读到旧宠物快照也会在锁上失败。
        if (petMapper.selectByIdForUpdate(petId) == null) {
            throw BusinessException.of(PetErrorCode.PET_FORBIDDEN);
        }
        PetHealthSnapshotView before = snapshot(health);
        List<String> allergies = copy(req.getAllergies());
        List<String> chronicDiseases = copy(req.getChronicDiseases());
        String specialNotes = emptyToNull(req.getSpecialNotes());
        PetHealthSnapshotView after = PetHealthSnapshotView.builder()
                .allergies(allergies)
                .chronicDiseases(chronicDiseases)
                .specialNotes(specialNotes)
                .build();
        List<String> changedFields = changedFields(before, after);

        boolean isNew = health == null;
        if (isNew) {
            health = new PetHealthEntity();
            health.setPetId(petId);
        }
        health.setAllergies(writeList(allergies));
        health.setChronicDiseases(writeList(chronicDiseases));
        health.setSpecialNotes(specialNotes);
        health.setUpdatedByRole(roleOf(actor));
        if (isNew) {
            try {
                healthMapper.insert(health);
            } catch (DuplicateKeyException e) {
                // 两个首写者同时看到“无行”时由 uk_pet 兜底，后者按版本冲突处理。
                throw healthConflict();
            }
        } else if (healthMapper.updateHealthSnapshot(health) == 0) {
            // 行在锁内消失（例如被并发删除）；不写日志，按冲突让客户端重读。
            throw healthConflict();
        }
        // 无实际变化不落日志：历史只代表真实写入，不是每次点击的流水。
        if (!changedFields.isEmpty()) {
            insertChangeLog(petId, actor, changedFields, before, after);
        }
        // 重新读回以拿到数据库维护的 updated_at（并确认写入结果），避免出参时间陈旧。
        PetHealthEntity saved = health.getId() == null ? null : healthMapper.selectById(health.getId());
        return toHealthVo(saved == null ? health : saved, petId);
    }

    /**
     * 版本校验。行必须已被 SELECT ... FOR UPDATE 锁定；这里只做事务内的快速比较，
     * 不能把本方法单独用于无锁路径。
     */
    private static void assertHealthVersion(PetHealthEntity health, LocalDateTime expected) {
        if (health == null) {
            if (expected != null) {
                throw healthConflict(); // 客户端以为存在旧档案，实际已被删除/从未创建
            }
            return;
        }
        if (expected == null) {
            throw healthConflict(); // 普通编辑必须带读到的版本，不能裸写覆盖
        }
        if (!sameVersion(health.getUpdatedAt(), expected)) {
            throw healthConflict();
        }
    }

    private static boolean sameVersion(LocalDateTime actual, LocalDateTime expected) {
        return actual != null && expected != null
                && actual.truncatedTo(ChronoUnit.MILLIS).equals(expected.truncatedTo(ChronoUnit.MILLIS));
    }

    private static BusinessException healthConflict() {
        // 项目无独立 409 段；10005 是现有“重复/并发提交”类错误，由前端健康编辑识别为版本冲突。
        return new BusinessException(CommonErrorCode.DUPLICATE_REQUEST,
                "健康档案已被其他操作更新，请重新读取最新档案后再编辑");
    }

    private PetHealthSnapshotView snapshot(PetHealthEntity health) {
        return PetHealthSnapshotView.builder()
                .allergies(health == null ? List.of() : readList(health.getAllergies()))
                .chronicDiseases(health == null ? List.of() : readList(health.getChronicDiseases()))
                .specialNotes(health == null ? null : health.getSpecialNotes())
                .build();
    }

    private static List<String> changedFields(PetHealthSnapshotView before, PetHealthSnapshotView after) {
        List<String> changed = new ArrayList<>(3);
        if (!Objects.equals(before.getAllergies(), after.getAllergies())) {
            changed.add("allergies");
        }
        if (!Objects.equals(before.getChronicDiseases(), after.getChronicDiseases())) {
            changed.add("chronicDiseases");
        }
        if (!Objects.equals(before.getSpecialNotes(), after.getSpecialNotes())) {
            changed.add("specialNotes");
        }
        return changed;
    }

    private void insertChangeLog(Long petId, PetHealthActor actor, List<String> changedFields,
                                 PetHealthSnapshotView before, PetHealthSnapshotView after) {
        PetHealthChangeLogEntity log = new PetHealthChangeLogEntity();
        log.setPetId(petId);
        log.setStoreId(actor.getStoreId());
        log.setOperatorType(actor.getOperatorType());
        log.setOperatorId(actor.getOperatorId());
        log.setOperatorName(emptyToNull(actor.getOperatorName()));
        log.setOperatorRole(emptyToNull(actor.getOperatorRole()));
        log.setChangedFields(writeJson(changedFields));
        log.setBeforeJson(writeJson(before));
        log.setAfterJson(writeJson(after));
        healthChangeLogMapper.insert(log);
    }

    private String customerName(Long userId) {
        try {
            List<UserProfileView> profiles = userAccountApi.profilesByIds(List.of(userId));
            if (profiles == null) {
                return null;
            }
            return profiles.stream()
                    .filter(p -> Objects.equals(p.getUserId(), userId))
                    .map(UserProfileView::getNickname)
                    .filter(n -> !isBlank(n))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            // 操作人姓名是审计附加值，查询失败不阻断健康档案写入；operatorId 仍保留。
            log.warn("健康档案变更读取操作人姓名失败，降级为无姓名 userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    private static String roleOf(PetHealthActor actor) {
        return isBlank(actor.getOperatorRole()) ? "EMPLOYEE" : actor.getOperatorRole().trim();
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ---------- 内部 ----------

    /** 归属 + 存在性统一闸：不满足一律 20101（C 端任何读写必经）。 */
    private PetEntity requireManageable(Long userId, Long petId) {
        if (!userPetApi.canManage(userId, petId)) {
            throw BusinessException.of(PetErrorCode.PET_FORBIDDEN);
        }
        PetEntity pet = petMapper.selectById(petId);
        if (pet == null) {
            throw BusinessException.of(PetErrorCode.PET_FORBIDDEN);
        }
        return pet;
    }

    /** C12：头像 key 必须在写入前验证归属/场景与实际对象大小（5MiB 由同一配置提供）。 */
    private void validateAvatar(Long userId, String avatar) {
        String key = emptyToNull(avatar);
        if (key != null) {
            com.petplatform.common.storage.MediaObjectGuard.requireWritable(
                    media.getIfAvailable(), key, userId, "PET_AVATAR", maxAvatarBytes);
        }
    }

    private void applyBasics(PetEntity pet, String name, String species, String breed, String gender,
                             Boolean isNeutered, java.time.LocalDate birthday, Boolean birthdayApprox,
                             java.math.BigDecimal weightKg, String avatar, String remark) {
        pet.setName(name);
        pet.setSpecies(species);
        pet.setBreed(emptyToNull(breed));
        pet.setGender(gender == null || gender.isBlank() ? "UNKNOWN" : gender);
        pet.setIsNeutered(isNeutered != null && isNeutered);
        pet.setBirthday(birthday);
        pet.setBirthdayApprox(birthdayApprox != null && birthdayApprox);
        pet.setWeightKg(weightKg);
        pet.setSizeCategory(sizeCategoryResolver.resolve(weightKg));
        pet.setAvatar(emptyToNull(avatar));
        pet.setRemark(emptyToNull(remark));
    }

    private PetHealthEntity findHealth(Long petId) {
        return healthMapper.selectOne(Wrappers.<PetHealthEntity>lambdaQuery()
                .eq(PetHealthEntity::getPetId, petId));
    }

    PetVO toVo(PetEntity p, Long viewerId) {
        com.petplatform.common.storage.MediaStorage storage = media.getIfAvailable();
        return PetVO.builder()
                .id(p.getId()).name(p.getName()).species(p.getSpecies()).breed(p.getBreed())
                .gender(p.getGender()).isNeutered(p.getIsNeutered()).birthday(p.getBirthday())
                .birthdayApprox(p.getBirthdayApprox()).weightKg(p.getWeightKg())
                .sizeCategory(p.getSizeCategory()).avatar(p.getAvatar()).remark(p.getRemark())
                .avatarUrl(signAvatar(storage, p, viewerId))
                .vaccineSummary(p.getVaccineSummary()).status(p.getStatus()).createdAt(p.getCreatedAt())
                .build();
    }

    /**
     * 头像签名归属闸（ADR-029 §契约4）：avatar 是用户自填字段，presigned GET 一经签发即等于对象可读，
     * 故只允许 key 桶主 ∈ {查看者本人} ∪ {同样可管理该宠物的人}；其余一律不签（前端回退 avatar 原值/占位图）。
     */
    private String signAvatar(com.petplatform.common.storage.MediaStorage storage, PetEntity p, Long viewerId) {
        if (storage == null || p.getAvatar() == null) {
            return null;
        }
        Long owner = com.petplatform.common.storage.MediaStorage.keyOwner(p.getAvatar());
        if (owner == null || !(owner.equals(viewerId) || userPetApi.canManage(owner, p.getId()))) {
            return null;
        }
        return storage.presignGet(p.getAvatar(), com.petplatform.common.storage.MediaStorage.GET_TTL);
    }

    PetHealthVO toHealthVo(PetHealthEntity h, Long petId) {
        if (h == null) {
            return PetHealthVO.builder().petId(petId)
                    .allergies(List.of()).chronicDiseases(List.of())
                    .build();
        }
        return PetHealthVO.builder()
                .petId(petId)
                .allergies(readList(h.getAllergies()))
                .chronicDiseases(readList(h.getChronicDiseases()))
                .specialNotes(h.getSpecialNotes())
                .updatedByRole(h.getUpdatedByRole())
                .updatedAt(h.getUpdatedAt())
                .build();
    }

    private String writeList(List<String> list) {
        return list == null || list.isEmpty() ? null : writeJson(list);
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("健康档案 JSON 序列化失败", e);
        }
    }

    private List<String> readList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            // 脏数据不阻断读取：降级空数组（medical 同步链路未接通前该列只有本服务写入）
            log.warn("健康档案 JSON 反序列化失败，降级空数组: {}", e.getMessage());
            return List.of();
        }
    }

    private String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
