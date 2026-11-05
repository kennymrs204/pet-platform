package com.petplatform.boarding.web;

import com.petplatform.boarding.api.dto.CareActivityView;
import com.petplatform.boarding.service.BoardingStayService;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.employee.api.dto.EmployeeBrief;
import com.petplatform.pet.api.PetQueryApi;
import com.petplatform.pet.api.dto.PetSummary;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 全店「今日护理动态」聚合（ADR-032，B 端托管页右栏）。
 *
 * <p>与 {@code /kennels}、{@code /assignable-staff}、{@code /pending-appointments} 同族前缀
 * （都是"托管页的辅助数据源"），但刻意**新建控制器**而非挤进 {@code StoreBoardingPickController}
 * ——后者是并行会话的高频改动文件（ADR-018/030 都落在里面），同 ADR-019 §2「一律新建文件」的先例。
 *
 * <p>权限码复用 {@code boarding:care:view}（与住单列表同码：它就是同一块看板的数据源），零权限迁移；
 * 任务角色的范围收窄在 SQL 里完成（{@link BoardingStayService#careActivity}）。
 */
@RestController
@RequestMapping("/api/v1/store/boarding")
public class StoreBoardingActivityController {

    private final BoardingStayService boardingStayService;
    private final PetQueryApi petQueryApi;
    private final EmployeeQueryApi employeeQueryApi;

    public StoreBoardingActivityController(BoardingStayService boardingStayService, PetQueryApi petQueryApi,
                                           EmployeeQueryApi employeeQueryApi) {
        this.boardingStayService = boardingStayService;
        this.petQueryApi = petQueryApi;
        this.employeeQueryApi = employeeQueryApi;
    }

    /** @param date 业务日（ISO，缺省今日）；晚于今日 → 10001 */
    @GetMapping("/care-activity")
    @PreAuthorize("hasAuthority('perm:boarding:care:view')")
    public Result<List<CareActivityView>> careActivity(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        List<CareActivityView> rows = boardingStayService.careActivity(storeId, date, principal);
        if (rows.isEmpty()) {
            return Result.ok(rows);
        }
        List<Long> petIds = rows.stream().map(CareActivityView::getPetId).filter(Objects::nonNull).distinct().toList();
        List<Long> caretakerIds = rows.stream().map(CareActivityView::getCaretakerId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, PetSummary> pets = petQueryApi.summaries(petIds).stream()
                .collect(Collectors.toMap(PetSummary::getId, Function.identity(), (a, b) -> a));
        Map<Long, String> caretakers = caretakerIds.isEmpty() ? Map.of()
                : employeeQueryApi.briefsInStore(caretakerIds, storeId).stream()
                .collect(Collectors.toMap(EmployeeBrief::getEmployeeId, EmployeeBrief::getName, (a, b) -> a));
        rows.forEach(v -> {
            PetSummary pet = pets.get(v.getPetId());
            v.setPetName(pet == null ? null : pet.getName());
            v.setCaretakerName(caretakers.get(v.getCaretakerId()));
        });
        return Result.ok(rows);
    }

    private Long requireStoreCtx(AuthPrincipal principal) {
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }
}
