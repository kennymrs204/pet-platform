package com.petplatform.app.web;

import com.petplatform.app.service.StoreCustomerCommandService;
import com.petplatform.app.service.StoreCustomerQueryService;
import com.petplatform.app.service.StoreCustomerQueryService.CustomerDetail;
import com.petplatform.app.service.StoreCustomerQueryService.CustomerPetRow;
import com.petplatform.app.service.StoreCustomerQueryService.CustomerRow;
import com.petplatform.app.service.StoreCustomerQueryService.CustomerStats;
import com.petplatform.app.service.StoreCustomerQueryService.CustomerVisitRow;
import com.petplatform.appointment.api.dto.AppointmentCreateRequest;
import com.petplatform.appointment.api.dto.AppointmentCreatedView;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.order.api.StoreOrderQueryApi;
import com.petplatform.order.api.dto.StoreOrderView;
import com.petplatform.pet.api.dto.PetCreateRequest;
import com.petplatform.pet.api.dto.PetVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端客户管理（ADR-017 §5 只读视图 + ADR-057 代客建档 + ADR-058 改档/代建宠物 + 代客建单写侧）。
 * 读侧无副作用；写侧四个动作：建档 / 改档 / 代建宠物 / 代客建单（下单人=顾客，source=RECEPTIONIST）。
 * storeId 只取已校验的 X-Store-Id 上下文，入参不携带门店 id；
 * userId 不在本店客户集内一律 10004（与跨店看单同口径，防枚举）。
 * 聚合逻辑在 {@link StoreCustomerQueryService}、写侧编排在 {@link StoreCustomerCommandService}
 * （app 装配层，零直查他模块表）。
 */
@RestController
@RequestMapping("/api/v1/store/customers")
public class StoreCustomerController {

    private final StoreCustomerQueryService customerQueryService;
    private final StoreCustomerCommandService customerCommandService;
    private final StoreOrderQueryApi storeOrderQueryApi;

    public StoreCustomerController(StoreCustomerQueryService customerQueryService,
                                   StoreCustomerCommandService customerCommandService,
                                   StoreOrderQueryApi storeOrderQueryApi) {
        this.customerQueryService = customerQueryService;
        this.customerCommandService = customerCommandService;
        this.storeOrderQueryApi = storeOrderQueryApi;
    }

    /**
     * 门店代客建档（ADR-057）：手机号 + 姓名两个必填字段。
     * 手机号已存在（注册用户或他店/本店已建档）一律 20001；建档成功后本店客户列表即可见（口径已并入）。
     */
    @PostMapping
    @PreAuthorize("hasAuthority('perm:customer:create')")
    public Result<CustomerRow> create(@Valid @RequestBody CreateCustomerCommand command) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(customerCommandService.create(requireStoreCtx(principal), command.getPhone(), command.getNickname()));
    }

    /**
     * 门店侧编辑客户资料（ADR-058）：姓名 / 默认联系电话 / 客户备注三项；
     * 归属校验同读侧（不在本店客户集 → 10004 防枚举）。返回更新后的客户 360，前端可直接用它刷新。
     */
    @PutMapping("/{userId}")
    @PreAuthorize("hasAuthority('perm:customer:update')")
    public Result<CustomerDetail> update(@PathVariable String userId, @Valid @RequestBody UpdateCustomerCommand command) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        Long uid = parseUserId(userId);
        customerCommandService.update(storeId, uid, command.getNickname(), command.getDefaultContactPhone(), command.getRemark());
        return Result.ok(customerQueryService.detail(storeId, uid, principal));
    }

    /**
     * 门店代客建宠物（ADR-058）：字段与校验复用 C 端 {@code PetCreateRequest} 同一条路径
     * （尺寸按体重算、健康空档、owner=该客户）；归属校验同读侧（10004）。
     */
    @PostMapping("/{userId}/pets")
    @PreAuthorize("hasAuthority('perm:customer:update')")
    public Result<PetVO> createPet(@PathVariable String userId, @Valid @RequestBody PetCreateRequest request) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        return Result.ok(customerCommandService.createPet(storeId, parseUserId(userId), request));
    }

    /**
     * 门店代客建单（新增预约）：body 与 C 端建单端点逐字段同形（{@code AppointmentCreateRequest}），
     * 校验/落库/C 端通知走 C 端同一条链（source=RECEPTIONIST 可追溯）；顾客须在本店客户集、
     * 服务须属本店（均 10004 防枚举/防跨店）。返回创建视图（预约号/金额/支付时限）——**不代付**：
     * 落 PENDING_PAYMENT，顾客在 C 端「订单」页支付；B 端到店史/列表"有效预约"立即可见。
     */
    @PostMapping("/{userId}/appointments")
    @PreAuthorize("hasAuthority('perm:appointment:create')")
    public Result<AppointmentCreatedView> createAppointment(@PathVariable String userId,
                                                            @Valid @RequestBody AppointmentCreateRequest request) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        return Result.ok(customerCommandService.createAppointment(storeId, parseUserId(userId), request));
    }

    /** 建档请求体（校验先于服务执行；手机号格式与注册同规则）。 */
    @Data
    public static class CreateCustomerCommand {
        @NotBlank(message = "手机号必填")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        private String phone;

        @NotBlank(message = "姓名必填")
        @Size(max = 50, message = "姓名不超过 50 字")
        private String nickname;
    }

    /**
     * 改档请求体（ADR-058）：规则与 C 端 ProfileUpdateRequest 的对应字段一致——
     * 姓名必填 ≤50；默认联系电话"手机号或空"；备注 ≤500 且空=清空。
     * 手机号（登录凭证）、头像、偏好门店、营销订阅刻意不在体内（见 ADR-058 §字段裁决）。
     */
    @Data
    public static class UpdateCustomerCommand {
        @NotBlank(message = "客户姓名必填")
        @Size(max = 50, message = "客户姓名不超过 50 字")
        private String nickname;

        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "默认联系电话须为手机号或空")
        @Size(max = 20, message = "默认联系电话过长")
        private String defaultContactPhone;

        @Size(max = 500, message = "客户备注不超过 500 字")
        private String remark;
    }

    /** 客户订单史行（打通轮 P4）：快照金额，顾客信息不重复携带（外层详情已有）。 */
    @Data
    @Builder
    public static class CustomerOrderRow {
        private Long id;
        private String orderNo;
        private Long appointmentId;
        private String status;
        private Long amount;
        private Long paidAmount;
        private String paymentMethod;
        private java.time.LocalDateTime paidAt;
        private java.time.LocalDateTime createdAt;
        private String serviceName;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('perm:customer:view:store')")
    public Result<PageResult<CustomerRow>> list(@RequestParam(required = false) String tab,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(defaultValue = "1") int pageNum,
                                                @RequestParam(defaultValue = "20") int pageSize) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(customerQueryService.page(requireStoreCtx(principal), tab, keyword, pageNum, pageSize, principal));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('perm:customer:view:store')")
    public Result<CustomerStats> stats() {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(customerQueryService.stats(requireStoreCtx(principal), principal));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAuthority('perm:customer:view:store')")
    public Result<CustomerDetail> detail(@PathVariable String userId) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(customerQueryService.detail(requireStoreCtx(principal), parseUserId(userId), principal));
    }

    @GetMapping("/{userId}/pets")
    @PreAuthorize("hasAuthority('perm:customer:view:store')")
    public Result<List<CustomerPetRow>> pets(@PathVariable String userId) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(customerQueryService.petsOf(requireStoreCtx(principal), parseUserId(userId), principal));
    }

    /**
     * 到店史分页（ADR-017 §5 的偏移分页增量）：`limit` 钳制 1..100、`offset` 钳制 ≥0（服务层做），
     * 排序 service_date desc→id desc 前缀稳定，前端按「每页 10 条」翻页时每次只查当页。
     */
    @GetMapping("/{userId}/appointments")
    @PreAuthorize("hasAuthority('perm:customer:view:store')")
    public Result<List<CustomerVisitRow>> appointments(@PathVariable String userId,
                                                       @RequestParam(defaultValue = "20") int limit,
                                                       @RequestParam(defaultValue = "0") int offset) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(customerQueryService.visitsOf(requireStoreCtx(principal), parseUserId(userId), limit, offset, principal));
    }

    /**
     * 客户 360 订单史（订单管理链路打通 P4）：本店该客户的订单分页，排序 created_at desc→id desc。
     * 客户归属校验与读侧同口径（不在本店客户集 → 10004 防枚举）；权限码用 order:view:store——
     * 数据属 ord_order（表 Owner=order），与订单管理页同码；店长/前台/ADMIN 三角色本就同持两码。
     * 行数据取订单快照口径（amount=创建时快照），不带线下收款详情（那是订单页的登记/复核语境）。
     */
    @GetMapping("/{userId}/orders")
    @PreAuthorize("hasAuthority('perm:order:view:store')")
    public Result<PageResult<CustomerOrderRow>> orders(@PathVariable String userId,
                                                       @RequestParam(defaultValue = "1") int pageNum,
                                                       @RequestParam(defaultValue = "10") int pageSize) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        Long uid = parseUserId(userId);
        customerQueryService.requireCustomer(storeId, uid);
        PageResult<StoreOrderView> page = storeOrderQueryApi.pageInStoreByUser(storeId, uid, pageNum, pageSize);
        List<CustomerOrderRow> rows = page.getList().stream().map(StoreCustomerController::toCustomerOrderRow).toList();
        return Result.ok(PageResult.of(page.getTotal(), page.getPageNum(), page.getPageSize(), rows));
    }

    private static CustomerOrderRow toCustomerOrderRow(StoreOrderView o) {
        return CustomerOrderRow.builder()
                .id(o.getId()).orderNo(o.getOrderNo()).appointmentId(o.getAppointmentId())
                .status(o.getStatus()).amount(o.getAmount()).paidAmount(o.getPaidAmount())
                .paymentMethod(o.getPaymentMethod()).paidAt(o.getPaidAt()).createdAt(o.getCreatedAt())
                .serviceName(o.getServiceName())
                .build();
    }

    private Long requireStoreCtx(AuthPrincipal principal) {
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }

    /** userId 是字符串化雪花 ID：非数字一律 10001，不能让 NumberFormatException 冒成 10009。 */
    private static Long parseUserId(String raw) {
        if (raw == null || !raw.matches("\\d{1,19}")) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "非法客户 id");
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "非法客户 id");
        }
    }
}
