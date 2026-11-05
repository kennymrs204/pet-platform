package com.petplatform.appointment.web;

import com.petplatform.appointment.api.AppointmentQueryApi;
import com.petplatform.appointment.api.dto.AppointmentCancelView;
import com.petplatform.appointment.api.dto.AppointmentCreateRequest;
import com.petplatform.appointment.api.dto.AppointmentCreatedView;
import com.petplatform.appointment.api.dto.AppointmentSelfView;
import com.petplatform.appointment.api.dto.CancelAppointmentRequest;
import com.petplatform.appointment.api.dto.ModifyAppointmentRequest;
import com.petplatform.appointment.service.AppointmentCreateService;
import com.petplatform.appointment.service.AppointmentCancelService;
import com.petplatform.appointment.service.AppointmentModifyService;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.CurrentUserHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端"我的预约"（第八阶段 §4.2 / §3.1，ADR-016）。
 * userId 只取认证上下文；self 范围在 Service/查询层显式过滤，非本人一律 10004。
 */
@RestController
@RequestMapping("/api/v1/customer/appointments")
@Validated
public class CustomerAppointmentController {

    private final AppointmentCreateService createService;
    private final AppointmentCancelService cancelService;
    private final AppointmentModifyService modifyService;
    private final AppointmentQueryApi queryApi;

    public CustomerAppointmentController(AppointmentCreateService createService,
                                         AppointmentCancelService cancelService,
                                         AppointmentModifyService modifyService,
                                         AppointmentQueryApi queryApi) {
        this.createService = createService;
        this.cancelService = cancelService;
        this.modifyService = modifyService;
        this.queryApi = queryApi;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('perm:appointment:create')")
    public Result<AppointmentCreatedView> create(@Valid @RequestBody AppointmentCreateRequest request) {
        return Result.ok(createService.create(request));
    }

    /** 用户取消（ADR-023）：未付关单 / 已付进退款申请。 */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('perm:appointment:cancel:self')")
    public Result<AppointmentCancelView> cancel(@PathVariable Long id,
                                                @Valid @RequestBody CancelAppointmentRequest request) {
        return Result.ok(cancelService.cancel(CurrentUserHolder.require(), id,
                request.getRequestId(), request.getReason()));
    }

    /** 改期（ADR-026）：仅未支付可改时段/托管区间；换服务走取消+重订。 */
    @PutMapping("/{id}/modify")
    @PreAuthorize("hasAuthority('perm:appointment:modify')")
    public Result<AppointmentSelfView> modify(@PathVariable Long id,
                                              @Valid @RequestBody ModifyAppointmentRequest request) {
        return Result.ok(modifyService.modify(CurrentUserHolder.require(), id, request));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('perm:appointment:view:self')")
    public Result<PageResult<AppointmentSelfView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从1开始") int pageNum,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "每页至少1条")
            @Max(value = 100, message = "每页最多100条") int pageSize) {
        return Result.ok(queryApi.selfPage(CurrentUserHolder.require().userId(), status, pageNum, pageSize));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('perm:appointment:view:self')")
    public Result<AppointmentSelfView> detail(@PathVariable Long id) {
        AppointmentSelfView view = queryApi.selfDetail(CurrentUserHolder.require().userId(), id);
        if (view == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        return Result.ok(view);
    }
}
