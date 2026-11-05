package com.petplatform.pet.web;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.pet.api.dto.PetHealthChangeView;
import com.petplatform.pet.api.dto.StorePetDetailVO;
import com.petplatform.pet.api.dto.StorePetItemVO;
import com.petplatform.pet.api.dto.StorePetOverviewVO;
import com.petplatform.pet.api.dto.StorePetQuery;
import com.petplatform.pet.service.PetHealthHistoryService;
import com.petplatform.pet.service.StorePetQueryService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端宠物管理只读视图（ADR-014，第八阶段 §4.3 增补）。
 * 只读、无写副作用；storeId 只取已校验的 X-Store-Id 上下文，入参不携带门店 id。
 */
@RestController
@RequestMapping("/api/v1/store/pets")
public class StorePetController {

    private final StorePetQueryService storePetQueryService;
    private final PetHealthHistoryService healthHistoryService;

    public StorePetController(StorePetQueryService storePetQueryService,
                              PetHealthHistoryService healthHistoryService) {
        this.storePetQueryService = storePetQueryService;
        this.healthHistoryService = healthHistoryService;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('perm:pet:view')")
    public Result<StorePetOverviewVO> overview() {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(storePetQueryService.overview(requireStoreCtx(principal), principal));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('perm:pet:view')")
    public Result<PageResult<StorePetItemVO>> list(@Valid StorePetQuery query) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(storePetQueryService.page(requireStoreCtx(principal), principal, query));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('perm:pet:view')")
    public Result<StorePetDetailVO> detail(@PathVariable Long id) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(storePetQueryService.detail(requireStoreCtx(principal), principal, id));
    }

    /** 健康档案变更历史：只读 append-only 日志；范围与详情同源（当前门店/任务范围）。 */
    @GetMapping("/{id}/health-history")
    @PreAuthorize("hasAuthority('perm:pet:view') and hasAuthority('perm:pet:health:view')")
    public Result<List<PetHealthChangeView>> healthHistory(@PathVariable Long id) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(healthHistoryService.list(requireStoreCtx(principal), principal, id));
    }

    private Long requireStoreCtx(AuthPrincipal principal) {
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }
}
