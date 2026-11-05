package com.petplatform.boarding.web;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.common.time.BusinessDates;
import com.petplatform.schedule.api.ResourceOccupancyApi;
import com.petplatform.schedule.api.dto.KennelCapacityView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 笼位容量查询（P8 §4.3 GET /store/boarding/capacity?date=&type=，ADR-015）。
 * 口径=按日笼位占用数（ADR-005-7）；date 缺省业务今日（Asia/Shanghai）。
 */
@RestController
@RequestMapping("/api/v1/store/boarding")
public class StoreBoardingCapacityController {

    private static final Set<String> KENNEL_TYPES =
            Set.of("KENNEL_SMALL", "KENNEL_MEDIUM", "KENNEL_LARGE", "CAT_ROOM");

    private final ResourceOccupancyApi occupancyApi;

    public StoreBoardingCapacityController(ResourceOccupancyApi occupancyApi) {
        this.occupancyApi = occupancyApi;
    }

    @GetMapping("/capacity")
    @PreAuthorize("hasAuthority('perm:boarding:capacity:view')")
    public Result<List<KennelCapacityView>> capacity(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String type) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        if (type != null && !type.isBlank() && !KENNEL_TYPES.contains(type)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "非法笼位类型: " + type);
        }
        LocalDate day = date != null ? date : BusinessDates.today();
        return Result.ok(occupancyApi.kennelCapacity(storeCtx, day, type == null || type.isBlank() ? null : type));
    }
}
