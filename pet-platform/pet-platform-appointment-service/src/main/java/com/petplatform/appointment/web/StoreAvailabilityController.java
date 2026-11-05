package com.petplatform.appointment.web;

import com.petplatform.appointment.api.dto.AvailabilityView;
import com.petplatform.appointment.service.AppointmentAvailabilityService;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * B 端可约时段查询（客户 360 → 新增预约弹窗；与 C 端 /customer/store-services/{id}/availability 逐段对称）。
 * 与 catalog 的 StoreCatalogController 共享 /store/store-services 前缀、子路径不冲突
 * （CustomerCatalogController/CustomerAvailabilityController 已是同款先例）。
 * STORE 范围：storeId 只认已校验的 X-Store-Id 上下文；"服务属本店"与宠物主登记人反查在 service 内。
 */
@RestController
@RequestMapping("/api/v1/store/store-services")
public class StoreAvailabilityController {

    private final AppointmentAvailabilityService availabilityService;

    public StoreAvailabilityController(AppointmentAvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @GetMapping("/{id}/availability")
    @PreAuthorize("hasAuthority('perm:availability:view')")
    public Result<AvailabilityView> availability(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long petId) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(availabilityService.storeAvailability(requireStoreCtx(principal), id, date, petId));
    }

    private Long requireStoreCtx(AuthPrincipal principal) {
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }
}
