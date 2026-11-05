package com.petplatform.app.web;

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
import com.petplatform.fulfillment.api.ServiceRecordQueryApi;
import com.petplatform.fulfillment.api.dto.ServiceRecordBrief;
import com.petplatform.pet.api.PetQueryApi;
import com.petplatform.pet.api.dto.PetSummary;
import com.petplatform.schedule.api.ResourceOccupancyApi;
import com.petplatform.schedule.api.dto.KennelResourceView;
import lombok.Builder;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * B 端「洗护履约看板」（ADR-038，原洗澡服务页的数据源）。
 *
 * <p>为什么必须新开端点而不是让前端直连 {@code GET /store/appointments}：洗护队列需要
 * 「这是洗澡还是美容 / 需要哪种台位 / 这单做完没有」三类字段，预约看板一个都不给
 * （出参只有 {@code serviceName} 文本快照，而快照会随服务改名漂移，不能当判据），
 * 且预约看板闸是 {@code appointment:view:store}——美容师只有 {@code appointment:view:task}，根本进不去。
 *
 * <p>服务类型的唯一可靠判据是 {@code cat_service.code}：PET_BATH=洗澡、PET_GROOMING=美容，
 * 两者同落 {@code ful_bath_record}（V1:431 注释即"洗澡/美容服务记录"），所以本看板是一条队列两类服务，不拆两页。
 *
 * <p>范围（P8 §4.3 的双视图）在**服务端按权限码收窄**，不靠前端传参：
 * 持 {@code appointment:view:store}（店长/前台）看全店；只有 {@code task:accept} 的人（美容师/医生/遛狗师）
 * 强制只看指派给自己的。出参回带 {@code storeWideScope} 让前端如实标注视图，而不是自己猜权限。
 *
 * <p>刻意不做：① 台位分配关系不进队列行（占用条才是台位唯一真相，两处出必漂移）；
 * ② 客户身份不出（属 {@code customer:view:store} 域，同 ADR-028 对任务角色的口径）；
 * ③ 宠物健康明细不逐行拉（点开展开时再由 {@code /store/pets/{id}} 取，避免 N+1）。
 */
@RestController
@RequestMapping("/api/v1/store/grooming")
public class GroomingBoardController {

    /**
     * 队列只收"已确认及之后、未取消"的状态（PENDING_PAYMENT 不在履约看板上）。
     * CANCEL_REQUESTED 也在内（ADR-043 §5）：取消只是申请、台位还占着，门店必须看得见才能去审批；
     * 把它滤掉会让"待审核的取消"从看板上凭空消失。
     */
    private static final List<String> QUEUE_STATUSES =
            List.of("CONFIRMED", "CANCEL_REQUESTED", "WAIT_SERVICE", "SERVING", "COMPLETED");
    private static final String CODE_BATH = "PET_BATH";
    private static final String CODE_GROOMING = "PET_GROOMING";
    private static final Set<String> GROOMING_CODES = Set.of(CODE_BATH, CODE_GROOMING);
    /** 一日的洗护队列上限：门店量级下 200 已远超真实峰值，超出如实回 total 让前端提示截断。 */
    private static final int QUEUE_LIMIT = 200;
    /** 台位条要覆盖的两类资源（演示库里美容常不配台位，此时该段自然为空，不编台位）。 */
    private static final List<String> STATION_TYPES = List.of("BATH_STATION", "GROOMING_STATION");

    private final AppointmentQueryApi appointmentApi;
    private final CatalogQueryApi catalogApi;
    private final PetQueryApi petApi;
    private final EmployeeQueryApi employeeApi;
    private final ServiceRecordQueryApi recordApi;
    private final ResourceOccupancyApi occupancyApi;

    public GroomingBoardController(AppointmentQueryApi appointmentApi, CatalogQueryApi catalogApi,
                                  PetQueryApi petApi, EmployeeQueryApi employeeApi,
                                  ServiceRecordQueryApi recordApi, ResourceOccupancyApi occupancyApi) {
        this.appointmentApi = appointmentApi;
        this.catalogApi = catalogApi;
        this.petApi = petApi;
        this.employeeApi = employeeApi;
        this.recordApi = recordApi;
        this.occupancyApi = occupancyApi;
    }

    /** @param date 业务日（ISO），缺省=今日；@param type BATH|GROOMING，缺省=两类都要 */
    @GetMapping("/queue")
    @PreAuthorize("hasAuthority('perm:appointment:view:store') or hasAuthority('perm:task:accept')")
    public Result<QueueView> queue(@RequestParam(required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                   @RequestParam(required = false) String type) {
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
        String typeNorm = normalizeType(type);

        PageResult<AppointmentStoreView> page = appointmentApi.storeAppointments(AppointmentStoreQuery.builder()
                .storeId(storeId)
                .date(date)
                .statuses(QUEUE_STATUSES)
                .employeeId(employeeScope)
                .pageNum(1)
                .pageSize(QUEUE_LIMIT)
                .build());
        List<AppointmentStoreView> rows = page.getList();
        // 当天预约总数可能超过 QUEUE_LIMIT（含托管/遛狗等非洗护单）：截断了就如实说，不让"少几条"看起来像"没有几条"
        boolean truncated = page.getTotal() > rows.size();

        // 服务信息按去重后的上架服务 id 取（一家店通常 ≤10 个），不逐行调 storeServiceOf
        List<Long> storeServiceIds = rows.stream().map(AppointmentStoreView::getStoreServiceId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, StoreServiceView> services = storeServiceIds.stream()
                .map(catalogApi::storeServiceOf)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(StoreServiceView::getId, Function.identity(), (a, b) -> a));
        // 队列只留洗护两类服务：非洗护预约（托管/诊疗/遛狗）不出现在这张看板上
        List<AppointmentStoreView> grooming = rows.stream()
                .filter(v -> {
                    StoreServiceView s = services.get(v.getStoreServiceId());
                    return s != null && GROOMING_CODES.contains(s.getServiceCode())
                            && (typeNorm == null || typeNorm.equals(serviceTypeOf(s.getServiceCode())));
                })
                .toList();

        List<Long> petIds = grooming.stream().map(AppointmentStoreView::getPetId).filter(Objects::nonNull).distinct().toList();
        List<Long> employeeIds = grooming.stream().map(AppointmentStoreView::getEmployeeId).filter(Objects::nonNull).distinct().toList();
        Map<Long, PetSummary> pets = petApi.summaries(petIds).stream()
                .collect(Collectors.toMap(PetSummary::getId, Function.identity(), (a, b) -> a));
        Map<Long, String> avatarUrls = petApi.avatarUrls(petIds);
        Map<Long, EmployeeBrief> employees = employeeIds.isEmpty() ? Map.of()
                : employeeApi.briefsInStore(employeeIds, storeId).stream()
                .collect(Collectors.toMap(EmployeeBrief::getEmployeeId, Function.identity(), (a, b) -> a));
        Map<Long, ServiceRecordBrief> records = recordApi.bathRecordsByAppointments(
                grooming.stream().map(AppointmentStoreView::getId).toList());

        List<QueueItem> items = grooming.stream().map(v -> {
            StoreServiceView s = services.get(v.getStoreServiceId());
            PetSummary pet = pets.get(v.getPetId());
            EmployeeBrief emp = v.getEmployeeId() == null ? null : employees.get(v.getEmployeeId());
            ServiceRecordBrief rec = records.get(v.getId());
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
                    .requiredResourceType(s == null ? null : s.getRequiredResourceType())
                    .pet(pet == null ? null : PetRef.builder()
                            .id(pet.getId()).name(pet.getName()).species(pet.getSpecies()).breed(pet.getBreed())
                            .gender(pet.getGender()).sizeCategory(pet.getSizeCategory())
                            .avatarUrl(avatarUrls.get(pet.getId())).build())
                    .employee(emp == null ? null : EmployeeRef.builder()
                            .id(emp.getEmployeeId()).name(emp.getName()).build())
                    .assignedToMe(employeeScope == null
                            ? (principal.employeeId() != null && principal.employeeId().equals(v.getEmployeeId()))
                            : true)
                    .record(rec == null ? null : RecordRef.builder()
                            .employeeId(rec.getEmployeeId())
                            .startedAt(rec.getStartedAt())
                            .finishedAt(rec.getFinishedAt())
                            .serviceItems(rec.getServiceItems())
                            .remark(rec.getRemark())
                            .photoCount(rec.getPhotoCount())
                            .build())
                    .build();
        }).toList();

        return Result.ok(QueueView.builder()
                .date(day)
                .canAssign(canAssign)
                .storeWideScope(storeWide)
                .truncated(truncated)
                .total((long) items.size())
                .list(items)
                .build());
    }

    /**
     * 台位占用条：只列 AVAILABLE 台位（停用/维修中的台位排期不可用，画出来只会误导排班），
     * 占用槽来自 {@code ResourceOccupancyApi.daySlotOccupancy}（ADR-016 内部早已实现，本次只是开 HTTP 出口）。
     */
    @GetMapping("/stations")
    @PreAuthorize("hasAuthority('perm:appointment:view:store') or hasAuthority('perm:task:accept')")
    public Result<List<StationView>> stations(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        LocalDate day = date == null ? BusinessDates.today() : date;
        List<StationView> out = new ArrayList<>();
        for (String type : STATION_TYPES) {
            Map<Long, Set<Integer>> occupancy = occupancyApi.daySlotOccupancy(storeId, type, day);
            for (KennelResourceView r : occupancyApi.availableResources(storeId, type)) {
                out.add(new StationView(r.getId(), r.getName(), r.getType(), r.getStatus(),
                        occupancy.getOrDefault(r.getId(), Set.of()).stream().sorted().toList()));
            }
        }
        return Result.ok(out);
    }

    private static String serviceTypeOf(String serviceCode) {
        return CODE_BATH.equals(serviceCode) ? "BATH" : "GROOMING";
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank() || "ALL".equalsIgnoreCase(type)) {
            return null;
        }
        String t = type.trim().toUpperCase();
        if (!t.equals("BATH") && !t.equals("GROOMING")) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "type 仅支持 BATH/GROOMING/ALL");
        }
        return t;
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
     * 队列信封。
     * {@code storeWideScope} 由服务端告知（只有 {@code task:accept} 的人被强制收窄成本人范围），
     * 前端据此如实标注"全店/我的任务"，不自己猜权限；
     * {@code total} 是**本次返回的洗护条数**（不是当天全类型预约数，那个数含托管/遛狗，报出来只会误导）；
     * {@code truncated} 表示当天预约超过 {@value #QUEUE_LIMIT} 条上限、队列可能不完整。
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
        /** BATH | GROOMING —— 由 cat_service.code 判定，不看服务名文本 */
        private String serviceType;
        private Integer durationMin;
        private String requiredResourceType;
        private PetRef pet;
        /** null = 尚未指派执行人 */
        private EmployeeRef employee;
        private Boolean assignedToMe;
        /** null = 尚未提交履约记录（前端据此显示"未开始/待完成"，不猜） */
        private RecordRef record;
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

    /** 履约记录摘要：不含照片 key/URL（同 ADR-032 的取舍，看板块不展图）。 */
    @Data
    @Builder
    public static class RecordRef {
        private Long employeeId;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
        private List<String> serviceItems;
        private String remark;
        private Integer photoCount;
    }

    @Data
    public static class StationView {
        private final Long resourceId;
        private final String name;
        private final String type;
        private final String status;
        /** 已占用的 30 分钟槽号（slot 20 = 10:00–10:30，ADR-005） */
        private final List<Integer> occupiedSlots;

        public StationView(Long resourceId, String name, String type, String status, List<Integer> occupiedSlots) {
            this.resourceId = resourceId;
            this.name = name;
            this.type = type;
            this.status = status;
            this.occupiedSlots = occupiedSlots;
        }
    }
}
