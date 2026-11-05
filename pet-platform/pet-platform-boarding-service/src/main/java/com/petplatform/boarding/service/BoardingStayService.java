package com.petplatform.boarding.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.appointment.api.AppointmentQueryApi;
import com.petplatform.appointment.api.dto.BoardingAppointmentView;
import com.petplatform.boarding.api.BoardingErrorCode;
import com.petplatform.boarding.api.BoardingStayProvisioningApi;
import com.petplatform.boarding.api.dto.BoardingStayDetailView;
import com.petplatform.boarding.api.dto.CareActivityView;
import com.petplatform.boarding.api.dto.CareRecordRequest;
import com.petplatform.boarding.api.dto.CareRecordView;
import com.petplatform.boarding.api.dto.CaretakerAssignRequest;
import com.petplatform.boarding.api.dto.CheckinRequest;
import com.petplatform.boarding.api.dto.CustomerCareRecordView;
import com.petplatform.boarding.api.dto.CustomerStayView;
import com.petplatform.boarding.api.event.BoardingCareAbnormalEvent;
import com.petplatform.boarding.api.event.BoardingCareRecordedEvent;
import com.petplatform.boarding.domain.BoardingStayEntity;
import com.petplatform.boarding.domain.DailyCareRecordEntity;
import com.petplatform.boarding.mapper.BoardingStayMapper;
import com.petplatform.boarding.mapper.DailyCareRecordMapper;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.time.BusinessDates;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.employee.api.dto.EmployeeBrief;
import com.petplatform.pet.api.PetQueryApi;
import com.petplatform.pet.api.dto.PetSummary;
import com.petplatform.schedule.api.ResourceErrorCode;
import com.petplatform.schedule.api.ResourceOccupancyApi;
import com.petplatform.schedule.api.dto.KennelResourceView;
import com.petplatform.schedule.api.dto.OccupancyView;
import com.petplatform.user.api.UserPetApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 托管入住单状态机 + 每日护理（ADR-015）。
 * 表边界：笼位占用经 ResourceOccupancyApi、预约迁移经 AppointmentQueryApi、
 * 归属经 UserPetApi、员工经 EmployeeQueryApi、宠物经 PetQueryApi——本服务零跨模块表访问。
 */
@Slf4j
@Service
public class BoardingStayService implements BoardingStayProvisioningApi {

    private static final Set<String> KENNEL_TYPES =
            Set.of("KENNEL_SMALL", "KENNEL_MEDIUM", "KENNEL_LARGE", "CAT_ROOM");
    /** 店范围读权限码（店长/前台；护理员仅 caretaker=自己，ADR-015 §3） */
    static final String CODE_STORE_WIDE = "appointment:view:store";
    /** 护理写权码：主责之外，持本码的本店员工可代录（ADR-015 §5 补记 2026-10-27，见 addCare） */
    static final String CODE_CARE_CREATE = "boarding:care:create";
    private static final int CARE_LIST_LIMIT = 200;
    /** 全店护理动态单次上限（B 端右栏一屏；量级远超此数说明当日记录异常，如实截断而非放大查询） */
    private static final int ACTIVITY_LIMIT = 100;

    private final BoardingStayMapper stayMapper;
    private final DailyCareRecordMapper careMapper;
    private final AppointmentQueryApi appointmentApi;
    private final ResourceOccupancyApi occupancyApi;
    private final PetQueryApi petApi;
    private final EmployeeQueryApi employeeApi;
    private final UserPetApi userPetApi;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final org.springframework.beans.factory.ObjectProvider<com.petplatform.common.storage.MediaStorage> media;

    public BoardingStayService(BoardingStayMapper stayMapper, DailyCareRecordMapper careMapper,
                               AppointmentQueryApi appointmentApi, ResourceOccupancyApi occupancyApi,
                               PetQueryApi petApi, EmployeeQueryApi employeeApi, UserPetApi userPetApi,
                               ApplicationEventPublisher events, ObjectMapper json,
                               org.springframework.beans.factory.ObjectProvider<com.petplatform.common.storage.MediaStorage> media) {
        this.stayMapper = stayMapper;
        this.careMapper = careMapper;
        this.appointmentApi = appointmentApi;
        this.occupancyApi = occupancyApi;
        this.petApi = petApi;
        this.employeeApi = employeeApi;
        this.userPetApi = userPetApi;
        this.events = events;
        this.json = json;
        this.media = media;
    }

    /** key 列表 → presigned URL 列表（ADR-029；未启用存储=空数组，非法 key 被端口拒签自然滤除）。 */
    List<String> signPhotos(List<String> keys) {
        if (keys == null) {
            return null;
        }
        com.petplatform.common.storage.MediaStorage storage = media.getIfAvailable();
        if (storage == null || keys.isEmpty()) {
            return List.of();
        }
        return keys.stream()
                .map(k -> storage.presignGet(k, com.petplatform.common.storage.MediaStorage.GET_TTL))
                .filter(Objects::nonNull).toList();
    }

    // ---------- 创建（支付事件链扩展点） ----------

    @Override
    @Transactional
    public void ensureExpected(Long appointmentId) {
        BoardingAppointmentView snap = appointmentApi.boardingSnapshot(appointmentId);
        if (snap == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        if (snap.getCheckinDate() == null || snap.getCheckoutDate() == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "非跨天托管预约，不创建入住单");
        }
        // 与宠物删除共用 pet_pet 行锁：删除已提交时拒绝新建住单；本事务先提交时删除重查会看到此单。
        PetSummary lockedPet = petApi.lockForUse(snap.getPetId());
        if (lockedPet == null || !"NORMAL".equals(lockedPet.getStatus())) {
            throw BusinessException.of(com.petplatform.pet.api.PetErrorCode.PET_FORBIDDEN);
        }
        if (stayMapper.countByAppointment(appointmentId) > 0) {
            return;
        }
        BoardingStayEntity stay = new BoardingStayEntity();
        stay.setAppointmentId(appointmentId);
        stay.setPetId(snap.getPetId());
        stay.setStoreId(snap.getStoreId());
        stay.setCheckinDate(snap.getCheckinDate());
        stay.setCheckoutDate(snap.getCheckoutDate());
        stay.setStatus("EXPECTED");
        stay.setVersion(0);
        try {
            stayMapper.insert(stay);
        } catch (DuplicateKeyException e) {
            // uk_appt 并发幂等
            log.debug("ensureExpected 并发幂等 appointmentId={}", appointmentId);
        }
    }

    @Override
    @Transactional
    public void cancelExpected(Long appointmentId) {
        // ADR-023 §3：仅 EXPECTED 逻辑删（deleted=delval=id 惯例由 @Table 手写 SQL 表达）；0 行静默幂等
        int affected = stayMapper.logicalDeleteExpectedByAppointment(appointmentId);
        if (affected == 0) {
            log.debug("cancelExpected 无活跃 EXPECTED 单，静默 appointmentId={}", appointmentId);
        }
    }

    // ---------- B 端：门店补建待入住单（ADR-016 事件链的漏单兜底入口） ----------

    /**
     * 由本店预约补建 EXPECTED 入住单。住单与预约 1:1（uk_appt）且 appointment_id NOT NULL，
     * 故不存在"凭空新建托管"——本入口只做一件事：把 ensureExpected 的幂等预生成在门店侧手动触发。
     * 校验口径与 checkin 对齐：跨店/不存在统一 10004（不回显存在性）、非跨天预约 10001、
     * 非 CONFIRMED（未支付/已取消/已完成）50003；已生成过则幂等返回既有单。
     */
    @Transactional
    public BoardingStayDetailView provisionFromAppointment(Long appointmentId, AuthPrincipal principal) {
        Long storeId = principal.storeCtx();
        BoardingAppointmentView snap = appointmentApi.boardingSnapshot(appointmentId);
        if (snap == null || !Objects.equals(snap.getStoreId(), storeId)) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        if (snap.getCheckinDate() == null || snap.getCheckoutDate() == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "非跨天托管预约，不可生成入住单");
        }
        if (!"CONFIRMED".equals(snap.getStatus())) {
            throw new BusinessException(BoardingErrorCode.ILLEGAL_STATE_TRANSITION,
                    "仅已支付（CONFIRMED）的托管预约可生成入住单，当前状态：" + snap.getStatus());
        }
        ensureExpected(appointmentId);
        BoardingStayEntity stay = stayMapper.byAppointment(appointmentId);
        if (stay == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        return detail(stay.getId(), principal);
    }

    // ---------- B 端：入住 ----------

    @Transactional
    public BoardingStayDetailView checkin(Long stayId, CheckinRequest req, AuthPrincipal principal) {
        Long storeId = principal.storeCtx();
        BoardingStayEntity stay = requireStayInStore(stayId, storeId);
        if (!"EXPECTED".equals(stay.getStatus())) {
            throw new BusinessException(BoardingErrorCode.ILLEGAL_STATE_TRANSITION, "仅待入住单可办理入住");
        }
        BoardingAppointmentView snap = appointmentApi.boardingSnapshot(stay.getAppointmentId());
        if (snap == null || snap.getCheckinDate() == null
                || !Objects.equals(snap.getStoreId(), storeId) || !Objects.equals(snap.getPetId(), stay.getPetId())) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        if (!"CONFIRMED".equals(snap.getStatus())) {
            throw new BusinessException(BoardingErrorCode.ILLEGAL_STATE_TRANSITION, "预约当前状态不可入住");
        }
        PetSummary pet = petApi.summaries(List.of(stay.getPetId())).stream().findFirst()
                .orElseThrow(() -> BusinessException.of(CommonErrorCode.NOT_FOUND));
        if (!"NORMAL".equals(pet.getStatus()) || pet.getSizeCategory() == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "宠物状态异常或未标定尺寸，不可入住");
        }

        LocalDate from = stay.getCheckinDate();
        LocalDate toExclusive = stay.getCheckoutDate(); // 区间 [checkin, checkout)，ADR-005-7
        Long kennelId = resolveKennel(req, stay.getAppointmentId(), storeId, pet, from, toExclusive);

        if (req.getCaretakerId() != null && !employeeApi.isActiveInStore(req.getCaretakerId(), storeId)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "护理员非本店有效员工");
        }
        if (stayMapper.markCheckedIn(stayId, storeId, kennelId, req.getCaretakerId(),
                emptyToNull(req.getSpecialInstructions())) == 0) {
            throw new BusinessException(BoardingErrorCode.ILLEGAL_STATE_TRANSITION, "入住单状态已变更，请刷新重试");
        }
        appointmentApi.startBoarding(stay.getAppointmentId());
        return detail(stayId, principal);
    }

    /**
     * 笼位决策：空=沿用预占；非空=校验+迁移；冲突由 schedule 抛 72001、无预占 40005。
     * 所有路径都先锁资源行，与停用入口串行化；沿用预占不强制 AVAILABLE，保留已确认托管兼容性。
     */
    private Long resolveKennel(CheckinRequest req, Long appointmentId, Long storeId,
                               PetSummary pet, LocalDate from, LocalDate toExclusive) {
        List<OccupancyView> occupancy = occupancyApi.occupancyOfAppointment(appointmentId).stream()
                .filter(o -> !o.getWorkDate().isBefore(from) && o.getWorkDate().isBefore(toExclusive))
                .toList();
        Long target;
        boolean sameAsPreAssigned;
        if (req.getKennelResourceId() == null) {
            if (occupancy.isEmpty()) {
                throw new BusinessException(ResourceErrorCode.KENNEL_CAPACITY_INSUFFICIENT,
                        "该预约无预占笼位，请指定入住笼位");
            }
            target = occupancy.get(0).getResourceId();
            sameAsPreAssigned = true;
        } else {
            target = req.getKennelResourceId();
            sameAsPreAssigned = !occupancy.isEmpty()
                    && occupancy.stream().allMatch(o -> target.equals(o.getResourceId()));
        }

        KennelResourceView locked = occupancyApi.lockResourceForUse(storeId, target);
        if (locked == null) {
            if (sameAsPreAssigned) {
                throw new BusinessException(ResourceErrorCode.KENNEL_CAPACITY_INSUFFICIENT,
                        "预占笼位不存在或不可用");
            }
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        if (!sameAsPreAssigned) {
            if (!"AVAILABLE".equals(locked.getStatus())) {
                throw new BusinessException(ResourceErrorCode.KENNEL_CAPACITY_INSUFFICIENT, "笼位不可用");
            }
            requireTypeMatch(locked.getType(), pet);
        }
        occupancyApi.confirmOrMoveKennel(target, appointmentId, from, toExclusive);
        return target;
    }

    /** 类型匹配服务端裁决（客户端不可声明）：CAT 仅 CAT_ROOM；其余按尺寸基准型，允许升级禁止降级。 */
    private void requireTypeMatch(String type, PetSummary pet) {
        if (!KENNEL_TYPES.contains(type)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "该资源不是笼位类型");
        }
        if ("CAT".equals(pet.getSpecies())) {
            if (!"CAT_ROOM".equals(type)) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "猫仅可使用猫舍(CAT_ROOM)");
            }
            return;
        }
        if ("CAT_ROOM".equals(type)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "猫舍仅供猫使用");
        }
        int base = rank("KENNEL_" + pet.getSizeCategory());
        if (rank(type) < base) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "禁止降级笼位（允许升级）");
        }
    }

    private static int rank(String kennelType) {
        return switch (kennelType) {
            case "KENNEL_SMALL" -> 1;
            case "KENNEL_MEDIUM" -> 2;
            case "KENNEL_LARGE" -> 3;
            default -> 0;
        };
    }

    // ---------- B 端：离店 ----------

    @Transactional
    public BoardingStayDetailView checkout(Long stayId, AuthPrincipal principal) {
        Long storeId = principal.storeCtx();
        BoardingStayEntity stay = requireStayInStore(stayId, storeId);
        if (!"CHECKED_IN".equals(stay.getStatus()) && !"DAILY_CARE".equals(stay.getStatus())) {
            throw new BusinessException(BoardingErrorCode.ILLEGAL_STATE_TRANSITION, "仅在住单可办理离店");
        }
        if (stayMapper.markCheckedOut(stayId, storeId) == 0) {
            throw new BusinessException(BoardingErrorCode.ILLEGAL_STATE_TRANSITION, "入住单状态已变更，请刷新重试");
        }
        appointmentApi.completeBoarding(stay.getAppointmentId());
        // 提前离店仅释放 [今日, checkoutDate) 的未来占用；正常日离店释放 0 行，历史绝不误删
        occupancyApi.releaseOccupancy(stay.getAppointmentId(), BusinessDates.today(), stay.getCheckoutDate());
        return detail(stayId, principal);
    }

    // ---------- B 端：在住单补派/改派主责护理员 ----------

    /**
     * {@code caretaker_id} 历史上是护理记录的唯一写闸（ADR-015 §5，2026-10-27 放宽为"主责 ∨ 持码代录"），
     * 而入住时"暂不指派"是合法路径
     * （{@link #checkin} 不强制 caretakerId）——于是漏派之后再没有入口补上，护理闭环卡在 70001。
     * 本方法只做这一件事：把在住单的护理员指向本店有效员工。
     * 授权沿用 boarding:checkin（与入住时指派同一码，零权限迁移）；错误码与 checkin 同口径：
     * 跨店/不存在 10004、非在住态 50003、非本店有效员工 10001、条件更新 0 行 50003。
     */
    @Transactional
    public BoardingStayDetailView assignCaretaker(Long stayId, CaretakerAssignRequest req, AuthPrincipal principal) {
        Long storeId = principal.storeCtx();
        BoardingStayEntity stay = requireStayInStore(stayId, storeId);
        if (!"CHECKED_IN".equals(stay.getStatus()) && !"DAILY_CARE".equals(stay.getStatus())) {
            throw new BusinessException(BoardingErrorCode.ILLEGAL_STATE_TRANSITION, "仅在住单可指派护理员");
        }
        if (!employeeApi.isActiveInStore(req.getCaretakerId(), storeId)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "护理员非本店有效员工");
        }
        if (stayMapper.reassignCaretaker(stayId, storeId, req.getCaretakerId()) == 0) {
            throw new BusinessException(BoardingErrorCode.ILLEGAL_STATE_TRANSITION, "入住单状态已变更，请刷新重试");
        }
        // 回读按门店范围而不是 detail()：detail() 的读侧范围只放行"本单护理员/店范围码"，
        // 把派给别人的人会被自己刚写成功的结果挡在 70001——写已生效却报失败，是撒谎。
        return toDetailView(requireStayInStore(stayId, storeId));
    }

    // ---------- B 端：护理记录 ----------

    @Transactional
    public CareRecordView addCare(Long stayId, CareRecordRequest req, AuthPrincipal principal) {
        Long storeId = principal.storeCtx();
        BoardingStayEntity stay = requireStayInStore(stayId, storeId);
        Long me = principal.employeeId();
        // 写闸（ADR-015 §5 补记 2026-10-27 用户拍板放宽）：该单主责 ∨ 持 boarding:care:create 的本店员工。
        // 代录场景（未指派/主责不在场）不再卡死闭环；记录的 caretaker_id = 实际写入人（append-only 审计），
        // 主责身份仍以 boa_boarding_stay.caretaker_id 为准。employeeId=0（如 ADMIN 代店无归属）不给写。
        if (me == null || me == 0L
                || (!me.equals(stay.getCaretakerId()) && !principal.permissions().contains(CODE_CARE_CREATE))) {
            throw new BusinessException(BoardingErrorCode.ONLY_ASSIGNED_CARETAKER, "仅该单主责护理员或持护理记录权限的员工可填写记录");
        }
        if (!"CHECKED_IN".equals(stay.getStatus()) && !"DAILY_CARE".equals(stay.getStatus())) {
            throw new BusinessException(BoardingErrorCode.ILLEGAL_STATE_TRANSITION, "仅在住单可添加护理记录");
        }
        LocalDate today = BusinessDates.today();
        LocalDate careDate = req.getCareDate() != null ? req.getCareDate() : today;
        if (careDate.isAfter(today) || careDate.isBefore(stay.getCheckinDate())
                || careDate.isAfter(stay.getCheckoutDate())) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "护理日期须在入住区间内且不晚于今日");
        }
        boolean abnormal = Boolean.TRUE.equals(req.getAbnormalFlag());
        if (abnormal && (req.getAbnormalDesc() == null || req.getAbnormalDesc().isBlank())) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "异常上报必须填写异常描述");
        }

        DailyCareRecordEntity record = new DailyCareRecordEntity();
        record.setBoardingStayId(stayId);
        record.setCareDate(careDate);
        record.setCaretakerId(me);
        record.setFeeding(writeJson(req.getFeeding()));
        record.setWatering(writeJson(req.getWatering()));
        record.setWalk(writeJson(req.getWalk()));
        record.setObservation(emptyToNull(req.getObservation()));
        record.setPhotos(req.getPhotos() == null || req.getPhotos().isEmpty() ? null : writeJson(req.getPhotos()));
        record.setAbnormalFlag(abnormal);
        record.setAbnormalDesc(abnormal ? req.getAbnormalDesc().trim() : null);
        careMapper.insert(record);

        // 每条护理记录都给顾客推站内信（2026-10-27 用户拍板 b 案；异常另有 CARE_ABNORMAL 通知店端，两者并存）。
        // ownerId 解析失败只降级为不推送（null，订阅方跳过），绝不能让收件人解析拖垮记录落库主链路。
        Long ownerId = null;
        if (stay.getAppointmentId() != null) {
            try {
                ownerId = appointmentApi.ownerIdOfAppointment(stay.getAppointmentId());
            } catch (RuntimeException e) {
                log.warn("护理记录通知收件人解析失败，跳过顾客推送 stayId={} appointmentId={}",
                        stayId, stay.getAppointmentId(), e);
            }
        }
        events.publishEvent(new BoardingCareRecordedEvent(
                stayId, stay.getAppointmentId(), stay.getPetId(), storeId, ownerId, careDate, abnormal,
                LocalDateTime.now()));

        if (abnormal) {
            // 不改入住单状态、不阻塞离店；订阅方 AFTER_COMMIT（notification 段前无消费者）
            events.publishEvent(new BoardingCareAbnormalEvent(
                    stayId, stay.getPetId(), storeId, careDate, LocalDateTime.now()));
        } else {
            stayMapper.markDailyCare(stayId); // CHECKED_IN→DAILY_CARE；0 行=已推进，正常
        }
        return toCareView(record);
    }

    public List<CareRecordView> listCare(Long stayId, AuthPrincipal principal) {
        return listCare(stayId, null, principal);
    }

    public List<CareRecordView> listCare(Long stayId, Long expectedPetId, AuthPrincipal principal) {
        BoardingStayEntity stay = requireVisibleStay(stayId, expectedPetId, principal);
        List<DailyCareRecordEntity> records = careMapper.selectList(Wrappers.<DailyCareRecordEntity>lambdaQuery()
                .eq(DailyCareRecordEntity::getBoardingStayId, stayId)
                .orderByDesc(DailyCareRecordEntity::getCareDate)
                .orderByDesc(DailyCareRecordEntity::getId)
                .last("LIMIT " + CARE_LIST_LIMIT));
        List<CareRecordView> views = records.stream().map(this::toCareView).toList();
        Set<Long> caretakerIds = records.stream()
                .map(DailyCareRecordEntity::getCaretakerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> names = caretakerIds.isEmpty() ? Map.of()
                : employeeApi.briefsInStore(caretakerIds, stay.getStoreId()).stream()
                .collect(Collectors.toMap(EmployeeBrief::getEmployeeId, EmployeeBrief::getName,
                        (a, b) -> a));
        views.forEach(v -> v.setCaretakerName(
                v.getCaretakerId() == null ? null : names.get(v.getCaretakerId())));
        return views;
    }

    /**
     * 全店某业务日的护理动态（ADR-032，替代 B 端"对在住前 5 单扇面拉 {@code /{id}/care-records} 再本地过滤"）。
     *
     * <p>范围与住单列表**同源**（ADR-015 §3）：店范围码看全店，任务角色只看自己被指派的单——判定下推进
     * SQL 的 WHERE，取回再 filter 会与 LIMIT 抢顺序（{@link #canView} 留给详情与写侧用）。
     *
     * <p>名字回填不在这里做：宠物名/护理员名属别的 Owner，由装配层控制器经 api 批量取（与住单列表同法）。
     *
     * @param date 业务日，缺省=今日（{@link BusinessDates}）；晚于今日 → 10001（记录不可能存在，静默返空会让人以为没人写）
     */
    public List<CareActivityView> careActivity(Long storeId, LocalDate date, AuthPrincipal principal) {
        LocalDate today = BusinessDates.today();
        LocalDate day = date == null ? today : date;
        if (day.isAfter(today)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "查询日期不能晚于今日");
        }
        Long caretakerScope = readCaretakerScope(principal);
        if (SCOPE_NOTHING.equals(caretakerScope)) {
            return List.of(); // 与列表同口径：没有"自己的单"，不必打库
        }
        return careMapper.selectStoreActivity(storeId, day, caretakerScope, ACTIVITY_LIMIT).stream()
                .map(r -> CareActivityView.builder()
                        .recordId(r.getRecordId()).stayId(r.getStayId()).petId(r.getPetId())
                        .careDate(r.getCareDate()).caretakerId(r.getCaretakerId())
                        .feeding(readJson(r.getFeeding(), CareRecordRequest.Feeding.class))
                        .watering(readJson(r.getWatering(), CareRecordRequest.Watering.class))
                        .walk(readJson(r.getWalk(), CareRecordRequest.Walk.class))
                        .observation(r.getObservation())
                        .abnormalFlag(Boolean.TRUE.equals(r.getAbnormalFlag()))
                        .abnormalDesc(r.getAbnormalDesc())
                        .build())
                .toList();
    }

    // ---------- B 端：详情/列表范围 ----------

    public BoardingStayDetailView detail(Long stayId, AuthPrincipal principal) {
        return detail(stayId, null, principal);
    }

    public BoardingStayDetailView detail(Long stayId, Long expectedPetId, AuthPrincipal principal) {
        BoardingStayEntity stay = requireVisibleStay(stayId, expectedPetId, principal);
        return toDetailView(stay);
    }

    /** 列表行范围过滤（controller 取回本店 stays 后调用）：店范围码全见，否则仅 caretaker=自己。 */
    public boolean canView(Long stayCaretakerId, AuthPrincipal principal) {
        Long me = principal.employeeId();
        return isStoreWide(principal) || (me != null && me != 0L && me.equals(stayCaretakerId));
    }

    /** 任务角色但拿不到员工身份：看不到任何单（哨兵值，绝不与真实雪花 id 相同）。 */
    public static final Long SCOPE_NOTHING = -1L;

    /**
     * 住单**读侧范围**的唯一下推值（ADR-015 §3）：
     * {@code null}=全店（持 {@code appointment:view:store}）；否则=该员工主责的单。
     *
     * <p>为什么要单独一个方法而不是让调用方各自 `canView` 过滤：过滤发生在 `LIMIT` 之后就会丢单
     * （ADR-032 §问题3），而三个读侧出口（在住列表/历史分页/护理动态）必须同一条口径。
     * 无员工身份者拿到 {@link #SCOPE_NOTHING}——它匹配不到任何行，调用方可直接短路不打库。
     */
    public Long readCaretakerScope(AuthPrincipal principal) {
        if (isStoreWide(principal)) {
            return null;
        }
        return principal.isEmployee() ? principal.employeeId() : SCOPE_NOTHING;
    }

    private BoardingStayEntity requireVisibleStay(Long stayId, AuthPrincipal principal) {
        return requireVisibleStay(stayId, null, principal);
    }

    private BoardingStayEntity requireVisibleStay(Long stayId, Long expectedPetId, AuthPrincipal principal) {
        BoardingStayEntity stay = requireStayInStore(stayId, principal.storeCtx());
        if (expectedPetId != null && !Objects.equals(stay.getPetId(), expectedPetId)) {
            // 目标宠物不匹配与不存在同码，避免把住单 ID 当越权凭证
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        if (!canView(stay.getCaretakerId(), principal)) {
            throw new BusinessException(BoardingErrorCode.ONLY_ASSIGNED_CARETAKER);
        }
        return stay;
    }

    private BoardingStayEntity requireStayInStore(Long stayId, Long storeId) {
        BoardingStayEntity stay = stayId == null ? null : stayMapper.selectById(stayId);
        if (stay == null || !Objects.equals(stay.getStoreId(), storeId)) {
            // 跨店与不存在同码（不回显存在性）
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        return stay;
    }

    private static boolean isStoreWide(AuthPrincipal principal) {
        return principal.permissions().contains(CODE_STORE_WIDE);
    }

    // ---------- C 端：托管日报 ----------

    public CustomerStayView customerDetail(Long stayId, AuthPrincipal principal) {
        BoardingStayEntity stay = requireCustomerStay(stayId, principal);
        String caretakerName = stay.getCaretakerId() == null ? null
                : employeeApi.briefsInStore(List.of(stay.getCaretakerId()), stay.getStoreId()).stream()
                        .findFirst().map(EmployeeBrief::getName).orElse(null);
        return CustomerStayView.builder()
                .id(stay.getId()).petId(stay.getPetId()).status(stay.getStatus())
                .checkinDate(stay.getCheckinDate()).checkoutDate(stay.getCheckoutDate())
                .actualCheckinAt(stay.getActualCheckinAt()).actualCheckoutAt(stay.getActualCheckoutAt())
                .specialInstructions(stay.getSpecialInstructions()).caretakerName(caretakerName)
                .build();
    }

    /**
     * C 端「我的托管」列表（ADR-021）：stay 表无 user_id，归属经
     * appointment-api.boardingAppointmentIdsOfUser 推导（跨 Owner 边只走 api，表边界同 ADR-015）。
     * status 逗号多值、空=全部；排序 checkin_date desc→id desc；视图与 detail 同款脱敏。
     */
    public com.petplatform.common.result.PageResult<CustomerStayView> customerList(
            AuthPrincipal principal, String status, int pageNum, int pageSize) {
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), 100);
        List<String> statuses = parseStayStatuses(status);
        List<Long> apptIds = appointmentApi.boardingAppointmentIdsOfUser(principal.userId());
        if (apptIds.isEmpty()) {
            return com.petplatform.common.result.PageResult.of(0, page, size, List.of());
        }
        long total = stayMapper.countCustomerStays(apptIds, statuses);
        List<BoardingStayEntity> rows = total == 0 ? List.of()
                : stayMapper.selectCustomerStays(apptIds, statuses, (page - 1) * size, size);
        Map<Long, String> caretakerNames = caretakerNamesByStore(rows);
        return com.petplatform.common.result.PageResult.of(total, page, size,
                rows.stream().map(s -> toCustomerStayView(s, caretakerNames)).toList());
    }

    /** ABNORMAL 为 ADR-015 预留态，可查不可写；非法值 10001。 */
    private static final java.util.Set<String> STAY_STATUSES =
            java.util.Set.of("EXPECTED", "CHECKED_IN", "DAILY_CARE", "CHECKED_OUT", "ABNORMAL");

    private static List<String> parseStayStatuses(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        List<String> out = new java.util.ArrayList<>();
        for (String s : status.split(",")) {
            String v = s.trim();
            if (v.isEmpty()) {
                continue;
            }
            if (!STAY_STATUSES.contains(v)) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "非法状态值: " + v);
            }
            out.add(v);
        }
        return out.isEmpty() ? null : out.stream().distinct().toList();
    }

    /** 护理员姓名按店分组批量取（briefsInStore 单店口径；跨店住单天然分开）。 */
    private Map<Long, String> caretakerNamesByStore(List<BoardingStayEntity> rows) {
        Map<Long, List<Long>> byStore = new java.util.HashMap<>();
        for (BoardingStayEntity r : rows) {
            if (r.getCaretakerId() != null) {
                byStore.computeIfAbsent(r.getStoreId(), k -> new java.util.ArrayList<>()).add(r.getCaretakerId());
            }
        }
        Map<Long, String> names = new java.util.HashMap<>();
        byStore.forEach((storeId, ids) -> employeeApi.briefsInStore(ids.stream().distinct().toList(), storeId)
                .forEach(b -> names.put(b.getEmployeeId(), b.getName())));
        return names;
    }

    private static CustomerStayView toCustomerStayView(BoardingStayEntity stay, Map<Long, String> caretakerNames) {
        return CustomerStayView.builder()
                .id(stay.getId()).petId(stay.getPetId()).status(stay.getStatus())
                .checkinDate(stay.getCheckinDate()).checkoutDate(stay.getCheckoutDate())
                .actualCheckinAt(stay.getActualCheckinAt()).actualCheckoutAt(stay.getActualCheckoutAt())
                .specialInstructions(stay.getSpecialInstructions())
                .caretakerName(stay.getCaretakerId() == null ? null : caretakerNames.get(stay.getCaretakerId()))
                .build();
    }

    public List<CustomerCareRecordView> customerCareRecords(Long stayId, AuthPrincipal principal,
                                                            LocalDate startDate, LocalDate endDate) {
        BoardingStayEntity stay = requireCustomerStay(stayId, principal);
        LocalDate from = startDate != null && startDate.isAfter(stay.getCheckinDate()) ? startDate : stay.getCheckinDate();
        LocalDate to = endDate != null && endDate.isBefore(stay.getCheckoutDate()) ? endDate : stay.getCheckoutDate();
        return careMapper.selectList(Wrappers.<DailyCareRecordEntity>lambdaQuery()
                        .eq(DailyCareRecordEntity::getBoardingStayId, stayId)
                        .ge(DailyCareRecordEntity::getCareDate, from)
                        .le(DailyCareRecordEntity::getCareDate, to)
                        .orderByDesc(DailyCareRecordEntity::getCareDate)
                        .orderByDesc(DailyCareRecordEntity::getId)
                        .last("LIMIT " + CARE_LIST_LIMIT))
                .stream()
                .map(r -> {
                    List<String> photos = readPhotos(r.getPhotos());
                    return CustomerCareRecordView.builder()
                        .id(r.getId()).careDate(r.getCareDate())
                        .feeding(readJson(r.getFeeding(), CareRecordRequest.Feeding.class))
                        .watering(readJson(r.getWatering(), CareRecordRequest.Watering.class))
                        .walk(readJson(r.getWalk(), CareRecordRequest.Walk.class))
                        .observation(r.getObservation())
                        .photos(photos)
                        .photoUrls(signPhotos(photos))
                        .abnormalFlag(Boolean.TRUE.equals(r.getAbnormalFlag()))
                        .abnormalDesc(r.getAbnormalDesc())
                        .build();
                })
                .toList();
    }

    /** C 端授权：不存在/已删/非宠物归属统一 10004（不泄露存在性，ADR-015 §4）。 */
    private BoardingStayEntity requireCustomerStay(Long stayId, AuthPrincipal principal) {
        BoardingStayEntity stay = stayId == null ? null : stayMapper.selectById(stayId);
        if (stay == null || !userPetApi.canManage(principal.userId(), stay.getPetId())) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        return stay;
    }

    // ---------- 序列化/组装 ----------

    private BoardingStayDetailView toDetailView(BoardingStayEntity stay) {
        return BoardingStayDetailView.builder()
                .id(stay.getId()).appointmentId(stay.getAppointmentId()).petId(stay.getPetId())
                .storeId(stay.getStoreId()).status(stay.getStatus())
                .checkinDate(stay.getCheckinDate()).checkoutDate(stay.getCheckoutDate())
                .actualCheckinAt(stay.getActualCheckinAt()).actualCheckoutAt(stay.getActualCheckoutAt())
                .kennelResourceId(stay.getKennelResourceId()).caretakerId(stay.getCaretakerId())
                .specialInstructions(stay.getSpecialInstructions())
                .build();
    }

    CareRecordView toCareView(DailyCareRecordEntity r) {
        List<String> photos = readPhotos(r.getPhotos());
        return CareRecordView.builder()
                .id(r.getId()).careDate(r.getCareDate()).caretakerId(r.getCaretakerId())
                .feeding(readJson(r.getFeeding(), CareRecordRequest.Feeding.class))
                .watering(readJson(r.getWatering(), CareRecordRequest.Watering.class))
                .walk(readJson(r.getWalk(), CareRecordRequest.Walk.class))
                .observation(r.getObservation())
                .photos(photos)
                .photoUrls(signPhotos(photos))
                .abnormalFlag(Boolean.TRUE.equals(r.getAbnormalFlag()))
                .abnormalDesc(r.getAbnormalDesc())
                .build();
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("护理记录 JSON 序列化失败", e);
        }
    }

    private <T> T readJson(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readValue(raw, type);
        } catch (Exception e) {
            log.warn("护理记录 JSON 解析失败降级 null: {}", e.getMessage());
            return null;
        }
    }

    private List<String> readPhotos(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
