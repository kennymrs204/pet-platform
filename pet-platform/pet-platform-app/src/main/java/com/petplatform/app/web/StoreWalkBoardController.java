package com.petplatform.app.web;

import com.petplatform.app.service.StoreWalkPhotoService;
import com.petplatform.app.service.StoreWalkTrackService;
import com.petplatform.appointment.api.AppointmentQueryApi;
import com.petplatform.appointment.api.dto.AppointmentStoreQuery;
import com.petplatform.appointment.api.dto.AppointmentStoreView;
import com.petplatform.catalog.api.CatalogQueryApi;
import com.petplatform.catalog.api.dto.StoreServiceView;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.common.time.BusinessDates;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.employee.api.dto.EmployeeBrief;
import com.petplatform.fulfillment.api.ServiceRecordCommandApi;
import com.petplatform.fulfillment.api.ServiceRecordQueryApi;
import com.petplatform.fulfillment.api.dto.WalkRecordBrief;
import com.petplatform.fulfillment.api.dto.WalkTrackPoint;
import com.petplatform.fulfillment.api.dto.WalkTrackView;
import com.petplatform.pet.api.PetQueryApi;
import com.petplatform.pet.api.dto.PetSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * B 端「遛狗履约看板」读侧（ADR-049，遛狗服务页的数据源）。
 *
 * <p>为什么必须新开端点而不是复用洗护看板：{@code GroomingBoardController} 的队列**刻意排除**非洗护单
 * （ADR-038：类型判据 = {@code cat_service.code} ∈ {PET_BATH, PET_GROOMING}，托管/诊疗/遛狗一律不进），
 * 而遛狗单落的是另一张履约表 {@code ful_walk_record}（完成链 {@code TaskCompletionService} 按 code 分流），
 * {@code bathRecordsByAppointments} 根本看不见它——硬塞进洗护看板等于把两类不同表的数据混在一套出参里，
 * 所以照同一模式另开一 controller，而不是给洗护端点加类型参数改语义。
 *
 * <p>范围口径与 ADR-038 完全一致，在**服务端按权限码收窄**，不靠前端传参：
 * 持 {@code appointment:view:store}（店长/前台/代入的 ADMIN）看全店；只有 {@code task:accept} 的人
 * （遛狗师等）强制只看指派给自己的。出参回带 {@code storeWideScope} 让前端如实标注视图。
 *
 * <p>刻意不做：① 没有 stations 端点——遛狗不占台位（遛狗上架行 {@code required_resource_type} 应为空，
 * 资源枚举里也没有遛狗资源，P5 §54-55）；② 客户身份不出（属 {@code customer:view:store} 域）；
 * ③ {@code distance_m/track_summary/poop_count} 不出现在任何出参里（全链路无写入入口，ADR-048 留 NULL）。
 */
@RestController
@RequestMapping("/api/v1/store/walking")
public class StoreWalkBoardController {

    /**
     * 与洗护看板同口径（ADR-038/ADR-043 §5）：队列只收"已确认及之后、未取消"的状态，
     * CANCEL_REQUESTED 也在内——取消只是申请、遛狗时段还占着，门店必须看得见才能去审批。
     */
    private static final List<String> QUEUE_STATUSES =
            List.of("CONFIRMED", "CANCEL_REQUESTED", "WAIT_SERVICE", "SERVING", "COMPLETED");
    private static final String CODE_WALK = "DOG_WALK";
    private static final Set<String> WALK_CODES = Set.of(CODE_WALK);
    /** 一日的遛狗队列上限：超出如实回 total 让前端提示截断，不让"少几条"看起来像"没有几条"。 */
    private static final int QUEUE_LIMIT = 200;

    private final AppointmentQueryApi appointmentApi;
    private final CatalogQueryApi catalogApi;
    private final PetQueryApi petApi;
    private final EmployeeQueryApi employeeApi;
    private final ServiceRecordQueryApi recordApi;
    private final StoreWalkPhotoService photoService;
    private final StoreWalkTrackService trackService;

    public StoreWalkBoardController(AppointmentQueryApi appointmentApi, CatalogQueryApi catalogApi,
                                    PetQueryApi petApi, EmployeeQueryApi employeeApi,
                                    ServiceRecordQueryApi recordApi, StoreWalkPhotoService photoService,
                                    StoreWalkTrackService trackService) {
        this.appointmentApi = appointmentApi;
        this.catalogApi = catalogApi;
        this.petApi = petApi;
        this.employeeApi = employeeApi;
        this.recordApi = recordApi;
        this.photoService = photoService;
        this.trackService = trackService;
    }

    /** @param date 业务日（ISO），缺省=今日 */
    @GetMapping("/queue")
    @PreAuthorize("hasAuthority('perm:appointment:view:store') or hasAuthority('perm:task:accept')")
    public Result<QueueView> queue(@RequestParam(required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        boolean storeWide = principal.permissions().contains("appointment:view:store");
        // ADR-054：调度能力由服务端告知（持 assign/reassign 才可指派）——前端按此分流指派控件，不猜角色
        boolean canAssign = principal.permissions().contains("appointment:assign")
                || principal.permissions().contains("appointment:reassign");
        Long employeeScope = storeWide ? null : principal.employeeId();
        LocalDate day = date == null ? BusinessDates.today() : date;
        if (!storeWide && (employeeScope == null || employeeScope == 0L)) {
            // 只有 task:accept 却没有可用员工身份（例如纯 C 端账号）：没有"我的任务"这回事，直接空集，不越权看全店
            return Result.ok(QueueView.builder().date(day).storeWideScope(false).canAssign(canAssign).truncated(false)
                    .total(0L).list(List.of()).build());
        }

        PageResult<AppointmentStoreView> page = appointmentApi.storeAppointments(AppointmentStoreQuery.builder()
                .storeId(storeId)
                .date(date)
                .statuses(QUEUE_STATUSES)
                .employeeId(employeeScope)
                .pageNum(1)
                .pageSize(QUEUE_LIMIT)
                .build());
        List<AppointmentStoreView> rows = page.getList();
        boolean truncated = page.getTotal() > rows.size();

        // 服务信息按去重后的上架服务 id 取（一家店通常 ≤10 个），不逐行调 storeServiceOf
        List<Long> storeServiceIds = rows.stream().map(AppointmentStoreView::getStoreServiceId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, StoreServiceView> services = storeServiceIds.stream()
                .map(catalogApi::storeServiceOf)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(StoreServiceView::getId, Function.identity(), (a, b) -> a));
        // 队列只留遛狗：非遛狗预约（洗澡/美容/托管/诊疗）不出现在这张看板上（与洗护看板互斥，判据同源）
        List<AppointmentStoreView> walking = rows.stream()
                .filter(v -> {
                    StoreServiceView s = services.get(v.getStoreServiceId());
                    return s != null && WALK_CODES.contains(s.getServiceCode());
                })
                .toList();

        List<Long> petIds = walking.stream().map(AppointmentStoreView::getPetId).filter(Objects::nonNull).distinct().toList();
        List<Long> employeeIds = walking.stream().map(AppointmentStoreView::getEmployeeId).filter(Objects::nonNull).distinct().toList();
        Map<Long, PetSummary> pets = petApi.summaries(petIds).stream()
                .collect(Collectors.toMap(PetSummary::getId, Function.identity(), (a, b) -> a));
        Map<Long, String> avatarUrls = petApi.avatarUrls(petIds);
        Map<Long, EmployeeBrief> employees = employeeIds.isEmpty() ? Map.of()
                : employeeApi.briefsInStore(employeeIds, storeId).stream()
                .collect(Collectors.toMap(EmployeeBrief::getEmployeeId, Function.identity(), (a, b) -> a));
        Map<Long, WalkRecordBrief> records = recordApi.walkRecordsByAppointments(
                walking.stream().map(AppointmentStoreView::getId).toList());
        // 轨迹与服务记录同批取：轨迹可能在服务中就已保存（记录尚未产生），所以它是行级独立字段
        Map<Long, WalkTrackView> tracks = recordApi.walkTracksByAppointments(
                walking.stream().map(AppointmentStoreView::getId).toList());

        List<QueueItem> items = walking.stream().map(v -> {
            StoreServiceView s = services.get(v.getStoreServiceId());
            PetSummary pet = pets.get(v.getPetId());
            EmployeeBrief emp = v.getEmployeeId() == null ? null : employees.get(v.getEmployeeId());
            WalkRecordBrief rec = records.get(v.getId());
            return QueueItem.builder()
                    .appointmentId(v.getId())
                    .appointmentNo(v.getAppointmentNo())
                    .serviceDate(v.getServiceDate())
                    .startTime(v.getStartTime())
                    .endTime(v.getEndTime())
                    .status(v.getStatus())
                    .source(v.getSource())
                    .serviceName(v.getServiceName())
                    .price(v.getPriceSnapshot())
                    .storeServiceId(v.getStoreServiceId())
                    .serviceId(s == null ? null : s.getServiceId())
                    .serviceCode(s == null ? null : s.getServiceCode())
                    .serviceType(s == null ? null : serviceTypeOf(s.getServiceCode()))
                    .durationMin(s == null ? null : s.getDurationMin())
                    .pet(pet == null ? null : PetRef.builder()
                            .id(pet.getId()).name(pet.getName()).species(pet.getSpecies()).breed(pet.getBreed())
                            .gender(pet.getGender()).sizeCategory(pet.getSizeCategory())
                            .avatarUrl(avatarUrls.get(pet.getId())).build())
                    .employee(emp == null ? null : EmployeeRef.builder()
                            .id(emp.getEmployeeId()).name(emp.getName()).build())
                    .assignedToMe(employeeScope == null
                            ? (principal.employeeId() != null && principal.employeeId().equals(v.getEmployeeId()))
                            : true)
                    .record(rec == null ? null : WalkRecordRef.builder()
                            .employeeId(rec.getEmployeeId())
                            .startedAt(rec.getStartedAt())
                            .finishedAt(rec.getFinishedAt())
                            .durationMin(rec.getDurationMin())
                            .remark(rec.getRemark())
                            .byManager(rec.getByManager())
                            .photoKeys(rec.getPhotos())
                            .photoUrls(photoService.signPhotos(rec.getPhotos()))
                            .build())
                    .track(tracks.get(v.getId()))
                    .build();
        }).toList();

        return Result.ok(QueueView.builder()
                .date(day)
                .storeWideScope(storeWide)
                .canAssign(canAssign)
                .truncated(truncated)
                .total((long) items.size())
                .list(items)
                .build());
    }

    /**
     * 给<b>已完成</b>的遛狗记录追加照片（2026-10-24）。
     *
     * <p>复用既有权限码 {@code task:finish}：能把这条服务"做完"的人，才有资格补它的照片
     * （执行人本人，或持门店范围码的店长/前台）。真正的写入校验全在
     * {@link StoreWalkPhotoService}：门店/人员/服务类型/完成态/key 归属/对象存在性。
     *
     * <p>路径变量加 {@code \d+}：非数字子路径不得走进 Long 转换（同托管页 V3.2.1 的教训）。
     */
    @PostMapping("/{appointmentId:\\d+}/photos")
    @PreAuthorize("hasAuthority('perm:task:finish')")
    public Result<StoreWalkPhotoService.WalkPhotosView> addPhotos(@PathVariable Long appointmentId,
                                                                  @Valid @RequestBody WalkPhotoRequest request) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(photoService.add(requireStoreCtx(principal), principal,
                appointmentId, request.getMediaKeys()));
    }

    /** 照片关联请求：只收对象 key（二进制不进业务表，ADR-029）。数量上限与 API 常量单一来源。 */
    @Data
    public static class WalkPhotoRequest {
        @NotEmpty(message = "至少选择一张照片")
        @Size(max = ServiceRecordCommandApi.MAX_WALK_PHOTOS, message = "单次最多上传 9 张照片")
        private java.util.List<String> mediaKeys;
    }

    @PostMapping("/{appointmentId:\\d+}/track")
    @PreAuthorize("hasAuthority('perm:task:finish')")
    public Result<WalkTrackView> saveTrack(@PathVariable Long appointmentId,
                                           @Valid @RequestBody WalkTrackRequest request) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(trackService.save(requireStoreCtx(principal), principal, appointmentId,
                request.getTrackId(), request.getPausedSec() == null ? 0 : request.getPausedSec(),
                request.getPoints()));
    }

    /**
     * 轨迹保存请求。服务端只接收**原始坐标**：距离/时长/点数由服务端清洗后派生，
     * 入参里没有"客户端算好的里程"这种可伪造字段。
     */
    @Data
    public static class WalkTrackRequest {
        @NotBlank(message = "trackId 必填")
        @Size(min = 8, max = 64, message = "trackId 须为 8~64 位")
        @Pattern(regexp = "[A-Za-z0-9_-]+", message = "trackId 仅允许字母数字/下划线/短横线")
        private String trackId;

        @Min(value = 0, message = "暂停时长不能为负")
        @Max(value = ServiceRecordCommandApi.MAX_TRACK_PAUSE_SEC, message = "暂停时长超过上限")
        private Integer pausedSec;

        @NotEmpty(message = "轨迹点不能为空")
        @Size(max = ServiceRecordCommandApi.MAX_TRACK_POINTS, message = "轨迹点超过上限")
        @Valid
        private java.util.List<WalkTrackPoint> points;
    }

    private static String serviceTypeOf(String serviceCode) {
        return CODE_WALK.equals(serviceCode) ? "WALK" : "OTHER";
    }

    private Long requireStoreCtx(AuthPrincipal principal) {
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }

    /* ---- 出参 ---- */

    /**
     * 队列信封。语义与洗护看板一致：{@code storeWideScope} 由服务端告知，前端据此如实标注"全店/我的任务"；
     * {@code total} 是本次返回的**遛狗条数**（不是当天全类型预约数）；{@code truncated} 表示当天预约
     * 超过 {@value #QUEUE_LIMIT} 条上限、队列可能不完整。
     */
    @Data
    @Builder
    public static class QueueView {
        private final LocalDate date;
        private final boolean storeWideScope;
        /** 调用者是否持调度权限（appointment:assign/reassign，ADR-054）——前端据此渲染/隐藏指派控件，不猜角色 */
        private final boolean canAssign;
        private final boolean truncated;
        private final long total;
        private final List<QueueItem> list;
    }

    @Data
    @Builder
    public static class QueueItem {
        private Long appointmentId;
        private String appointmentNo;
        private LocalDate serviceDate;
        private LocalTime startTime;
        private LocalTime endTime;
        private String status;
        private String source;
        private String serviceName;
        /** 分（Long → Jackson 字符串化） */
        private Long price;
        private Long storeServiceId;
        private Long serviceId;
        private String serviceCode;
        /** 恒 WALK（DOG_WALK 之外的不进这条队列）；保留字段是为与洗护看板同构，前端不用分叉 */
        private String serviceType;
        private Integer durationMin;
        private PetRef pet;
        /** null = 尚未指派执行人 */
        private EmployeeRef employee;
        private Boolean assignedToMe;
        /** null = 尚未提交履约记录（前端据此显示"未开始/待完成"，不猜） */
        private WalkRecordRef record;
        /** null = 未采集轨迹；服务中也可能已有（轨迹在完成前就能保存），故与 record 平级 */
        private WalkTrackView track;
    }

    @Data
    @Builder
    public static class PetRef {
        private Long id;
        private String name;
        private String species;
        private String breed;
        private String gender;
        private String sizeCategory;
        /** presigned GET（ADR-029）；签不出为 null → 前端回落爪印占位 */
        private String avatarUrl;
    }

    @Data
    @Builder
    public static class EmployeeRef {
        private Long id;
        private String name;
    }

    /** 遛狗履约记录摘要：无执行项目（walk 表没有该列）；照片为完成后的补传，key + 现签 URL 同序。 */
    @Data
    @Builder
    public static class WalkRecordRef {
        private Long employeeId;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
        private Integer durationMin;
        private String remark;
        private Boolean byManager;
        /** 照片对象 key（{@code u-{uid}/WALK_PHOTO/…}）；无照片=空数组，绝不返回 null */
        private java.util.List<String> photoKeys;
        /** 与 photoKeys 同序的 presigned GET（600s）；存储未启用时为空数组 */
        private java.util.List<String> photoUrls;
    }
}
