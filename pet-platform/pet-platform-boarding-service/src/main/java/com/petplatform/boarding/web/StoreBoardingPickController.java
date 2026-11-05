package com.petplatform.boarding.web;

import com.petplatform.appointment.api.BoardingLeadQueryApi;
import com.petplatform.appointment.api.dto.BoardingLeadView;
import com.petplatform.boarding.api.dto.KennelOptionView;
import com.petplatform.boarding.service.KennelOptionService;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.common.time.BusinessDates;
import com.petplatform.employee.api.EmployeeRosterApi;
import com.petplatform.employee.api.dto.StoreStaffView;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 托管域的入住辅助数据源（ADR-018 两个选择器 + ADR-030 待支付看板）。
 * 均为只读、无副作用；storeId 只取已校验的 X-Store-Id 上下文，入参不携带门店 id。
 * 权限码复用既有码，零迁移：
 * - /kennels 用 boarding:capacity:view（与同前缀的容量看板同族）；
 * - /assignable-staff 用 boarding:checkin（名单只服务于"入住时指派主责护理员"，
 *   与 emp:view 区分开——后者是员工档案目录，护理员角色并不持有）；
 * - /pending-appointments 用 boarding:care:view（与住单列表同码：它就是同一张看板的"为什么这里没有它"答案）。
 */
@RestController
@RequestMapping("/api/v1/store/boarding")
public class StoreBoardingPickController {

    /** 待支付看板默认预览窗（今日起的入住日跨度） */
    private static final int DEFAULT_LEAD_DAYS = 14;
    private static final int MAX_LEAD_DAYS = 60;
    private static final int LEAD_LIMIT = 100;

    private final KennelOptionService kennelOptionService;
    private final EmployeeRosterApi employeeRosterApi;
    private final BoardingLeadQueryApi boardingLeadQueryApi;
    private final PetQueryApi petQueryApi;
    private final BoardingCustomerAssembler customerAssembler;

    public StoreBoardingPickController(KennelOptionService kennelOptionService, EmployeeRosterApi employeeRosterApi,
                                       BoardingLeadQueryApi boardingLeadQueryApi, PetQueryApi petQueryApi,
                                       BoardingCustomerAssembler customerAssembler) {
        this.kennelOptionService = kennelOptionService;
        this.employeeRosterApi = employeeRosterApi;
        this.boardingLeadQueryApi = boardingLeadQueryApi;
        this.petQueryApi = petQueryApi;
        this.customerAssembler = customerAssembler;
    }

    /** 可选笼位：给 stayId 时按该住单计划区间标注可用性；不给则按 date（缺省今日）单日。 */
    @GetMapping("/kennels")
    @PreAuthorize("hasAuthority('perm:boarding:capacity:view')")
    public Result<List<KennelOptionView>> kennels(
            @RequestParam(required = false) Long stayId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(kennelOptionService.options(requireStoreCtx(principal), stayId, date));
    }

    /** 本店可指派在职员工（姓名 + 该店角色中文名），供入住时指派主责护理员。 */
    @GetMapping("/assignable-staff")
    @PreAuthorize("hasAuthority('perm:boarding:checkin')")
    public Result<List<StoreStaffView>> assignableStaff() {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(employeeRosterApi.staffInStore(requireStoreCtx(principal)));
    }

    /**
     * 本店「已下单待支付」的托管预约（ADR-030）。
     *
     * <p>它们**不会**出现在住单列表里：住单由支付成功事件链预生成（ADR-016），未支付单被超时 Job 取消时
     * 还要连带回收，预生成只会制造孤儿。本端点是这类预约在 B 端唯一的可见出口——只读、不含任何写动作入口，
     * 收款仍在 C 端完成；店员若拿到已付的漏单，走 {@code POST /store/boarding-stays/from-appointment/{id}}。
     *
     * @param days 入住日起的预览天数（缺省 14，上限 60）；越界 10001，不静默钳制
     */
    @GetMapping("/pending-appointments")
    @PreAuthorize("hasAuthority('perm:boarding:care:view')")
    public Result<List<PendingBoardingAppointment>> pendingAppointments(
            @RequestParam(required = false) Integer days) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        int window = days == null ? DEFAULT_LEAD_DAYS : days;
        if (window < 1 || window > MAX_LEAD_DAYS) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "预览天数须在 1.." + MAX_LEAD_DAYS + " 之间");
        }
        LocalDate today = BusinessDates.today();
        List<BoardingLeadView> leads =
                boardingLeadQueryApi.pendingBoardingAppointments(storeId, today, today.plusDays(window - 1), LEAD_LIMIT);
        if (leads.isEmpty()) {
            return Result.ok(List.of());
        }
        List<Long> petIds = leads.stream().map(BoardingLeadView::getPetId).filter(Objects::nonNull).distinct().toList();
        Map<Long, PetSummary> pets = petQueryApi.summaries(petIds).stream()
                .collect(Collectors.toMap(PetSummary::getId, Function.identity(), (a, b) -> a));
        Map<Long, String> avatarUrls = petQueryApi.avatarUrls(petIds);
        Map<Long, BoardingCustomerAssembler.CustomerRef> customers = customerAssembler.byPet(petIds, principal);

        return Result.ok(leads.stream().map(v -> {
            PetSummary pet = pets.get(v.getPetId());
            return PendingBoardingAppointment.builder()
                    .id(v.getId())
                    .appointmentNo(v.getAppointmentNo())
                    .status(v.getStatus())
                    .checkinDate(v.getBoardingCheckinDate())
                    .checkoutDate(v.getBoardingCheckoutDate())
                    .nights(v.getBoardingCheckoutDate() == null || v.getBoardingCheckinDate() == null
                            ? 0 : (int) (v.getBoardingCheckoutDate().toEpochDay() - v.getBoardingCheckinDate().toEpochDay()))
                    .serviceName(v.getServiceName())
                    .priceSnapshot(v.getPriceSnapshot())
                    .expireAt(v.getExpireAt())
                    .createdAt(v.getCreatedAt())
                    .pet(pet == null ? null : StoreBoardingController.PetRef.builder()
                            .id(pet.getId()).name(pet.getName()).species(pet.getSpecies())
                            .breed(pet.getBreed()).gender(pet.getGender()).avatar(pet.getAvatar())
                            .avatarUrl(avatarUrls.get(pet.getId())).build())
                    .customer(customers.get(v.getPetId()))
                    .build();
        }).toList());
    }

    private Long requireStoreCtx(AuthPrincipal principal) {
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }

    /** 待支付托管预约行（预约快照 + 宠物/客户引用；无任何可操作入口）。 */
    @Data
    @Builder
    public static class PendingBoardingAppointment {
        private Long id;
        private String appointmentNo;
        /** 恒 PENDING_PAYMENT，回显供前端与验收审计不变量 */
        private String status;
        private LocalDate checkinDate;
        private LocalDate checkoutDate;
        /** 晚数 = checkout − checkin（ADR-025 同口径）。int 不经 Jackson 字符串化，保持数字 */
        private int nights;
        private String serviceName;
        /** 下单快照金额（分；Long→字符串） */
        private Long priceSnapshot;
        /** 到点自动取消并释放笼位预占 */
        private LocalDateTime expireAt;
        private LocalDateTime createdAt;
        private StoreBoardingController.PetRef pet;
        /** 客户（宠物主登记人）；无 customer:view:store 时为 null */
        private BoardingCustomerAssembler.CustomerRef customer;
    }
}
