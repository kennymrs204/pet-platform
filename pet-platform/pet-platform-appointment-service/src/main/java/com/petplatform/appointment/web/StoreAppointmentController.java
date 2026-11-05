package com.petplatform.appointment.web;

import com.petplatform.appointment.api.AppointmentQueryApi;
import com.petplatform.appointment.api.dto.AppointmentStoreQuery;
import com.petplatform.appointment.api.dto.AppointmentStoreView;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.employee.api.dto.EmployeeBrief;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * B 端门店预约列表（P8 §4.3 GET /store/appointments?date=&status=&employeeId=）。
 * 首页「今日待办」数据源：date 缺省=业务今日（Asia/Shanghai，ADR-013）。
 * STORE 范围：store_id 只认已校验的 X-Store-Id 上下文，入参不含 storeId。
 */
@RestController
@RequestMapping("/api/v1/store/appointments")
public class StoreAppointmentController {

    private static final Set<String> STATUSES = Set.of(
            "PENDING_PAYMENT", "CONFIRMED", "CANCEL_REQUESTED", "WAIT_SERVICE", "SERVING", "COMPLETED", "CANCELLED");

    private final AppointmentQueryApi appointmentQueryApi;
    private final PetQueryApi petQueryApi;
    private final EmployeeQueryApi employeeQueryApi;

    public StoreAppointmentController(AppointmentQueryApi appointmentQueryApi,
                                      PetQueryApi petQueryApi, EmployeeQueryApi employeeQueryApi) {
        this.appointmentQueryApi = appointmentQueryApi;
        this.petQueryApi = petQueryApi;
        this.employeeQueryApi = employeeQueryApi;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('perm:appointment:view:store')")
    public Result<PageResult<AppointmentBoardItem>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        List<String> statuses = parseStatuses(status);

        PageResult<AppointmentStoreView> page = appointmentQueryApi.storeAppointments(AppointmentStoreQuery.builder()
                .storeId(storeId).date(date).statuses(statuses).employeeId(employeeId)
                .pageNum(pageNum).pageSize(pageSize)
                .build());

        Map<Long, PetSummary> pets = petQueryApi
                .summaries(page.getList().stream().map(AppointmentStoreView::getPetId).distinct().toList())
                .stream().collect(Collectors.toMap(PetSummary::getId, Function.identity(), (a, b) -> a));
        Map<Long, EmployeeBrief> employees = employeeQueryApi
                .briefsInStore(page.getList().stream().map(AppointmentStoreView::getEmployeeId)
                        .filter(java.util.Objects::nonNull).distinct().toList(), storeId)
                .stream().collect(Collectors.toMap(EmployeeBrief::getEmployeeId, Function.identity(), (a, b) -> a));

        List<AppointmentBoardItem> items = page.getList().stream().map(v -> {
            PetSummary pet = pets.get(v.getPetId());
            EmployeeBrief emp = v.getEmployeeId() == null ? null : employees.get(v.getEmployeeId());
            return AppointmentBoardItem.builder()
                    .id(v.getId())
                    .appointmentNo(v.getAppointmentNo())
                    .serviceDate(v.getServiceDate())
                    .startTime(v.getStartTime())
                    .endTime(v.getEndTime())
                    .status(v.getStatus())
                    .serviceName(v.getServiceName())
                    .priceSnapshot(v.getPriceSnapshot())
                    .source(v.getSource())
                    .pet(pet == null ? null : PetRef.builder().id(pet.getId()).name(pet.getName())
                            .species(pet.getSpecies()).breed(pet.getBreed()).avatar(pet.getAvatar()).build())
                    .employee(emp == null ? null : EmployeeRef.builder().id(emp.getEmployeeId()).name(emp.getName()).build())
                    .build();
        }).toList();
        return Result.ok(PageResult.of(page.getTotal(), page.getPageNum(), page.getPageSize(), items));
    }

    private List<String> parseStatuses(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }
        List<String> list = java.util.Arrays.stream(status.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        for (String s : list) {
            if (!STATUSES.contains(s)) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "非法预约状态: " + s);
            }
        }
        return list;
    }

    private Long requireStoreCtx(AuthPrincipal principal) {
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }

    /** 预约看板条目（web 层组装：预约行 + 宠物/员工引用回显）。 */
    @Data
    @Builder
    public static class AppointmentBoardItem {
        private Long id;
        private String appointmentNo;
        private LocalDate serviceDate;
        private LocalTime startTime;
        private LocalTime endTime;
        private String status;
        private String serviceName;
        private Long priceSnapshot;
        private String source;
        private PetRef pet;
        private EmployeeRef employee;
    }

    @Data
    @Builder
    public static class PetRef {
        private Long id;
        private String name;
        private String species;
        private String breed;
        private String avatar;
    }

    @Data
    @Builder
    public static class EmployeeRef {
        private Long id;
        private String name;
    }
}
