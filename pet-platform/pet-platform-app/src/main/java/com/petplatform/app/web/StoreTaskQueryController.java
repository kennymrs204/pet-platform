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
import com.petplatform.pet.api.PetQueryApi;
import com.petplatform.pet.api.dto.PetSummary;
import lombok.Builder;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 服务人员侧「我的任务」只读端点（ADR-048 §14）。
 *
 * <p>为什么不能直接用 {@code GET /store/grooming/queue}：那个看板按
 * {@code cat_service.code ∈ {PET_BATH, PET_GROOMING}} 过滤（ADR-038 的口径），
 * <b>遛狗与诊疗的单在里面根本看不见</b>——而 {@code task:start}/{@code task:finish} 两枚权限码
 * 遛狗师和医生都持有。也就是说：能干活的人没有看自己活的入口。本端点补齐这一类，
 * 且只读、不改任何状态。
 *
 * <p>安全口径：{@code employeeId} <b>只从 token 取，绝不接受入参</b>（§七.C，
 * 与 ADR-041"禁止通过 userId 参数查别人的消息"同一条原则）。
 * 没有员工身份的账号（例如平台 ADMIN 代入本店时 {@code employeeId=0}）回空集而不是 10003——
 * "我没有个人任务"是事实，不该报成"你无权限"。
 */
@RestController
@RequestMapping("/api/v1/store/tasks")
public class StoreTaskQueryController {

    /** 默认"待办"= 已确认/待服务/服务中；COMPLETED 只在显式 ?status= 时出现（今日已完成）。 */
    private static final List<String> TODO_STATUSES = List.of("CONFIRMED", "WAIT_SERVICE", "SERVING");
    private static final Set<String> FILTERABLE = Set.of("CONFIRMED", "WAIT_SERVICE", "SERVING", "COMPLETED");
    private static final int LIMIT = 200;

    private final AppointmentQueryApi appointmentApi;
    private final CatalogQueryApi catalogApi;
    private final PetQueryApi petApi;

    public StoreTaskQueryController(AppointmentQueryApi appointmentApi, CatalogQueryApi catalogApi,
                                    PetQueryApi petApi) {
        this.appointmentApi = appointmentApi;
        this.catalogApi = catalogApi;
        this.petApi = petApi;
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('perm:task:accept') or hasAuthority('perm:appointment:view:store')")
    public Result<MineView> mine(@RequestParam(required = false)
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                 @RequestParam(required = false) String status) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        LocalDate day = date == null ? BusinessDates.today() : date;
        Long me = principal.employeeId();
        if (me == null || me == 0L) {
            return Result.ok(MineView.builder().date(day).statusFilter(TODO_STATUSES).employeeId(null)
                    .total(0L).todoTotal(0L).list(List.of()).build());
        }
        boolean explicitStatus = status != null && !status.isBlank();
        List<String> statuses = TODO_STATUSES;
        if (explicitStatus) {
            if (!FILTERABLE.contains(status)) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "status 只支持 " + FILTERABLE);
            }
            statuses = List.of(status);
        }
        PageResult<AppointmentStoreView> page = appointmentApi.storeAppointments(AppointmentStoreQuery.builder()
                .storeId(storeCtx)
                .date(day)
                .statuses(statuses)
                .employeeId(me)          // 强制本人，不接受外部入参
                .pageNum(1)
                .pageSize(LIMIT)
                .build());
        // todoTotal 是"全部 TODO 状态"的真实总数，不能跟着当前 UI 筛选走，也不能用列表长度。
        // 默认查询本身就是全 TODO，直接复用 page.getTotal()；显式单状态时另起一次同一
        // Service/Mapper 查询（pageSize=1），只取分页对象里的完整计数。
        long todoTotal = explicitStatus
                ? appointmentApi.storeAppointments(AppointmentStoreQuery.builder()
                        .storeId(storeCtx)
                        .date(day)
                        .statuses(TODO_STATUSES)
                        .employeeId(me)
                        .pageNum(1)
                        .pageSize(1)
                        .build()).getTotal()
                : page.getTotal();
        List<AppointmentStoreView> rows = page.getList() == null ? List.of() : page.getList();
        // CatalogQueryApi 只有单条读口，与洗护看板同一做法：按去重后的 id 逐条取
        Map<Long, StoreServiceView> services = new LinkedHashMap<>();
        for (Long sid : rows.stream().map(AppointmentStoreView::getStoreServiceId)
                .filter(Objects::nonNull).collect(Collectors.toSet())) {
            StoreServiceView s = catalogApi.storeServiceOf(sid);
            if (s != null) {
                services.put(sid, s);
            }
        }
        List<Long> petIds = rows.stream().map(AppointmentStoreView::getPetId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, PetSummary> pets = petIds.isEmpty() ? Map.of()
                : petApi.summaries(petIds).stream().collect(Collectors.toMap(
                        PetSummary::getId, Function.identity(), (a, b) -> a));

        List<TaskItem> items = rows.stream().map(v -> {
            StoreServiceView s = services.get(v.getStoreServiceId());
            PetSummary pet = pets.get(v.getPetId());
            return TaskItem.builder()
                    .appointmentId(v.getId())
                    .appointmentNo(v.getAppointmentNo())
                    .serviceDate(v.getServiceDate())
                    .startTime(v.getStartTime())
                    .endTime(v.getEndTime())
                    .status(v.getStatus())
                    .source(v.getSource())
                    .serviceName(v.getServiceName())
                    .serviceCode(s == null ? null : s.getServiceCode())
                    .durationMin(s == null ? null : s.getDurationMin())
                    .price(v.getPriceSnapshot())
                    .storeServiceId(v.getStoreServiceId())
                    .petId(v.getPetId())
                    .petName(pet == null ? null : pet.getName())
                    .employeeId(v.getEmployeeId())
                    .build();
        }).toList();
        return Result.ok(MineView.builder().date(day).statusFilter(statuses).employeeId(me)
                .total(items.size()).todoTotal(todoTotal).list(items).build());
    }

    /**
     * 出参带 {@code date}/{@code statusFilter}/{@code employeeId} 三件口径信息，
     * 让前端能如实标出"这是哪一天、哪一类、谁的"，而不是拿一个裸数组自己猜。
     * {@code total} 是本次返回条数（受 {@value #LIMIT} 上限保护），不是当天全部预约数。
     * {@code todoTotal} 是当天全部 TODO 状态（CONFIRMED/WAIT_SERVICE/SERVING）的真实总数，
     * 不受当前 status 筛选和列表 LIMIT 影响。
     */
    @Data
    @Builder
    public static class MineView {
        private final LocalDate date;
        private final List<String> statusFilter;
        private final Long employeeId;
        private final long total;
        private final long todoTotal;
        private final List<TaskItem> list;
    }

    @Data
    @Builder
    public static class TaskItem {
        private Long appointmentId;
        private String appointmentNo;
        private LocalDate serviceDate;
        private LocalTime startTime;
        private LocalTime endTime;
        private String status;
        private String source;
        private String serviceName;
        private String serviceCode;
        private Integer durationMin;
        private Long price;
        private Long storeServiceId;
        private Long petId;
        private String petName;
        private Long employeeId;
    }
}
