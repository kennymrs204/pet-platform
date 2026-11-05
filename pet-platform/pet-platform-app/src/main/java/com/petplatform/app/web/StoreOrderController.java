package com.petplatform.app.web;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.order.api.StoreOrderQueryApi;
import com.petplatform.order.api.dto.StoreOrderView;
import com.petplatform.payment.api.PaymentCommandApi;
import com.petplatform.payment.api.PaymentQueryApi;
import com.petplatform.payment.api.dto.OfflinePaymentView;
import com.petplatform.user.api.UserAccountApi;
import com.petplatform.user.api.dto.UserProfileView;
import lombok.Builder;
import lombok.Data;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * B 端门店订单（P8 §4.3 :227，权限码 order:view:store：ADMIN/店长/前台）+ 线下收款登记/复核
 * （ADR-006 §6，权限码 order:pay，双人原则由 payment 服务硬闸兜底）。
 *
 * 为什么在 app 装配层：依赖矩阵里 order 不依赖 user，"订单 + 下单客户"的拼装只能落在
 * 可同时依赖全部模块的组合根（StoreStatsController/EmployeeIdentityController 先例），零矩阵改动（ADR-019 §3）。
 *
 * 客户摘要复用 user 模块的 {@code UserAccountApi.profilesByIds}（ADR-017 §6 已建），**不再另建一套**（ADR-019 §2 合并约定）；
 * 该视图带完整 phone 仅供服务端内部脱敏，本类是唯一的出口，明文不出接口。
 *
 * 口径：日期为 created_at 闭区间；跨店或不存在统一 10004；页签计数不做（P8 无计数端点）。
 */
@RestController
@RequestMapping("/api/v1/store/orders")
public class StoreOrderController {

    private static final Set<String> ORDER_STATUSES = Set.of(
            "WAIT_PAY", "PAID", "WAIT_SERVICE", "SERVING", "COMPLETED", "CANCELLED", "REFUNDING", "REFUNDED");

    private final StoreOrderQueryApi storeOrderQueryApi;
    private final UserAccountApi userAccountApi;
    private final EmployeeQueryApi employeeQueryApi;
    private final PaymentCommandApi paymentCommandApi;
    private final PaymentQueryApi paymentQueryApi;

    /** 导出同步落盘目录（空=不落盘只走下载）；compose 里挂载宿主 order 目录并经 PET_ORDER_EXPORT_DIR 注入。 */
    @Value("${pet.order.export-dir:}")
    private String exportDir;

    public StoreOrderController(StoreOrderQueryApi storeOrderQueryApi, UserAccountApi userAccountApi,
                                EmployeeQueryApi employeeQueryApi,
                                PaymentCommandApi paymentCommandApi, PaymentQueryApi paymentQueryApi) {
        this.storeOrderQueryApi = storeOrderQueryApi;
        this.userAccountApi = userAccountApi;
        this.employeeQueryApi = employeeQueryApi;
        this.paymentCommandApi = paymentCommandApi;
        this.paymentQueryApi = paymentQueryApi;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('perm:order:view:store')")
    public Result<PageResult<StoreOrderItem>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        Long storeId = requireStoreCtx();
        requireStatus(status);
        String kw = requireKeyword(keyword);
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "起始日期不能晚于结束日期");
        }
        PageResult<StoreOrderView> page = storeOrderQueryApi.pageInStore(storeId, status, dateFrom, dateTo, kw, pageNum, pageSize);
        Map<Long, UserProfileView> customers = customersOf(page.getList());
        List<StoreOrderItem> items = page.getList().stream().map(o -> toItem(o, customers)).toList();
        return Result.ok(PageResult.of(page.getTotal(), page.getPageNum(), page.getPageSize(), items));
    }

    /**
     * 导出对账单（打通轮 P5）：按当前筛选导出本店订单 CSV（与 list 同权限码/同参数校验）。
     * 分页循环拉全量（单页 100），超过 {@link #EXPORT_LIMIT} 行 → 10001 提示缩小区间；
     * CSV 带 UTF-8 BOM（Excel 直开不乱码）、CRLF；单元格做公式注入防护（=+-@ 开头加 '）。
     * 客户手机号沿用列表同一条脱敏出口（明文不出接口）。
     * 配置 pet.order.export-dir 时同一份字节同步落盘该目录（同名覆盖）；写失败 10009。
     */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('perm:order:view:store')")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String keyword) {
        Long storeId = requireStoreCtx();
        requireStatus(status);
        String kw = requireKeyword(keyword);
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "起始日期不能晚于结束日期");
        }
        List<StoreOrderView> all = new ArrayList<>();
        long total;
        int pageNum = 1;
        do {
            PageResult<StoreOrderView> page = storeOrderQueryApi.pageInStore(storeId, status, dateFrom, dateTo, kw, pageNum++, 100);
            total = page.getTotal();
            all.addAll(page.getList());
        } while (all.size() < total && all.size() < EXPORT_LIMIT);
        if (total > EXPORT_LIMIT) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "符合条件的数据超过 " + EXPORT_LIMIT + " 行，请缩小筛选范围后导出");
        }
        Map<Long, UserProfileView> customers = customersOf(all);
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("订单号,状态,服务项目,金额(元),实收(元),支付方式,下单时间,支付时间,预约号,客户昵称,客户手机号\r\n");
        for (StoreOrderView o : all) {
            UserProfileView c = customers.get(o.getUserId());
            OrderCustomer cc = OrderCustomer.of(c);
            csv.append(String.join(",",
                    csvCell(o.getOrderNo()),
                    csvCell(STATUS_LABEL.getOrDefault(o.getStatus(), o.getStatus())),
                    csvCell(o.getServiceName()),
                    csvCell(fenToYuan(o.getAmount())),
                    csvCell(fenToYuan(o.getPaidAmount())),
                    csvCell(o.getPaymentMethod() == null ? "" : PAYMENT_LABEL.getOrDefault(o.getPaymentMethod(), o.getPaymentMethod())),
                    csvCell(o.getCreatedAt() == null ? "" : o.getCreatedAt().format(TS)),
                    csvCell(o.getPaidAt() == null ? "" : o.getPaidAt().format(TS)),
                    csvCell(o.getAppointmentId() == null ? "" : String.valueOf(o.getAppointmentId())),
                    csvCell(cc == null ? "" : cc.getNickname()),
                    csvCell(cc == null ? "" : cc.getPhoneMasked())));
            csv.append("\r\n");
        }
        String filename = "对账单-" + storeId + "-" + LocalDate.now() + ".csv";
        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        writeExportCopy(exportDir, filename, body);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    /**
     * 导出同步落盘：配置了 pet.order.export-dir 时，把与下载完全相同的字节写到该目录（同名覆盖，一天一份）。
     * 写失败按 10009 拒绝本次导出——不静默降级，落盘是显式要求而非附赠。
     */
    static void writeExportCopy(String exportDir, String filename, byte[] body) {
        if (exportDir == null || exportDir.isBlank()) {
            return;
        }
        try {
            Path dir = Path.of(exportDir);
            Files.createDirectories(dir);
            Files.write(dir.resolve(filename), body);
        } catch (IOException e) {
            throw new BusinessException(CommonErrorCode.SYSTEM_ERROR, "对账单落盘失败，请检查导出目录配置");
        }
    }

    /** 导出行数上限：超出直接拒绝（前端提示缩小区间），不做静默截断。 */
    private static final int EXPORT_LIMIT = 5000;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Map<String, String> STATUS_LABEL = Map.of(
            "WAIT_PAY", "待支付", "PAID", "已支付", "WAIT_SERVICE", "待服务", "SERVING", "服务中",
            "COMPLETED", "已完成", "CANCELLED", "已取消", "REFUNDING", "退款中", "REFUNDED", "已退款");

    private static final Map<String, String> PAYMENT_LABEL = Map.of("ONLINE", "线上", "OFFLINE", "线下");

    /** 分转元的纯文本（两位小数），null → 空串。 */
    /** 分 → 元两位小数；null=空单元格。包级可见供同包单测直测。 */
    static String fenToYuan(Long fen) {
        return fen == null ? "" : BigDecimal.valueOf(fen, 2).toPlainString();
    }

    /**
     * CSV 单元格：含逗号/引号/换行则整体加引号（内部引号翻倍）；
     * =+-@ 开头追加 ' 前缀防 Excel 公式注入（OWASP CSV Injection）。
     * 包级可见供同包单测直测。
     */
    static String csvCell(String v) {
        if (v == null || v.isEmpty()) {
            return "";
        }
        String s = v;
        if (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@") || s.startsWith("\t") || s.startsWith("\r")) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /**
     * 订单号前缀搜索口径：空白=不过滤；非空白但 &lt;2 字符 → 10001（防单字符全店扫）；
     * 只收订单号字符集（字母/数字/连字符），拦掉 %/_ 等 LIKE 通配符注入。
     */
    private static String requireKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String kw = keyword.trim();
        if (kw.length() < 2) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "搜索至少输入 2 个字符");
        }
        if (!kw.matches("[A-Za-z0-9-]+")) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "订单号只支持字母、数字和连字符");
        }
        return kw;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('perm:order:view:store')")
    public Result<StoreOrderItem> detail(@PathVariable Long id) {
        Long storeId = requireStoreCtx();
        StoreOrderView order = storeOrderQueryApi.detailInStore(storeId, id);
        if (order == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        StoreOrderItem item = toItem(order, customersOf(List.of(order)));
        item.setOfflinePayment(offlineInfo(id));
        return Result.ok(item);
    }

    /**
     * 线下收款登记（ADR-006 §6）：只写 OFFLINE PROCESSING 流水，<b>不推进订单</b>。
     * 登记≠到账，推进只能经 {@link #confirmOfflinePayment} 由他人复核完成（双人原则）。
     * 权限闸 order:pay 与线上支付同一把（店长/前台/ADMIN，V2 既有）。
     */
    @PostMapping("/{id}/offline-payment")
    @PreAuthorize("hasAuthority('perm:order:pay')")
    public Result<OfflinePaymentInfo> registerOfflinePayment(@PathVariable Long id,
                                                             @Valid @RequestBody(required = false) OfflinePaymentRegisterRequest body) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx();
        StoreOrderView order = requireOrderInStore(storeId, id);
        if (!"WAIT_PAY".equals(order.getStatus())) {
            throw new BusinessException(CommonErrorCode.ILLEGAL_STATE_TRANSITION, "订单当前状态不可登记线下收款");
        }
        OfflinePaymentView view = paymentCommandApi.registerOffline(
                id, order.getAmount(), principal.userId(), body == null ? null : body.getNote());
        return Result.ok(OfflinePaymentInfo.of(view, registrantName(view.getRegisteredBy())));
    }

    /**
     * 线下收款复核（ADR-006 §6 双人原则）：复核人≠登记人是 payment 服务硬闸（10003），
     * 通过后同事务推进 订单 PAID → 预约 CONFIRMED → 入住单 EXPECTED。
     * 未登记/已复核/并发复核落败统一 10006。
     */
    @PostMapping("/{id}/offline-payment/confirm")
    @PreAuthorize("hasAuthority('perm:order:pay')")
    public Result<OfflinePaymentInfo> confirmOfflinePayment(@PathVariable Long id) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx();
        requireOrderInStore(storeId, id); // 越权口径与读侧一致：跨店统一 10004
        OfflinePaymentView view = paymentCommandApi.confirmOffline(id, principal.userId());
        return Result.ok(OfflinePaymentInfo.of(view, registrantName(view.getRegisteredBy())));
    }

    private StoreOrderView requireOrderInStore(Long storeId, Long orderId) {
        StoreOrderView order = storeOrderQueryApi.detailInStore(storeId, orderId);
        if (order == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        return order;
    }

    /** 详情附带的线下收款状态：无线下流水为 null；登记人姓名经 UserAccountApi 回显（只有 B 端能看）。 */
    private OfflinePaymentInfo offlineInfo(Long orderId) {
        OfflinePaymentView view = paymentQueryApi.latestOfflineByOrder(orderId);
        return view == null ? null : OfflinePaymentInfo.of(view, registrantName(view.getRegisteredBy()));
    }

    /** 登记人姓名：取员工名（工号主人）；非员工/查无回退 null，前端按空渲染。 */
    private String registrantName(Long userId) {
        if (userId == null) {
            return null;
        }
        var identity = employeeQueryApi.activeIdentityOf(userId);
        return identity == null ? null : identity.getName();
    }

    private void requireStatus(String status) {
        if (status != null && !status.isBlank() && !ORDER_STATUSES.contains(status)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "订单状态取值非法");
        }
    }

    private Long requireStoreCtx() {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }

    private Map<Long, UserProfileView> customersOf(List<StoreOrderView> orders) {
        if (orders.isEmpty()) {
            return Map.of();
        }
        return userAccountApi.profilesByIds(orders.stream().map(StoreOrderView::getUserId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(UserProfileView::getUserId, Function.identity(), (a, b) -> a));
    }

    private StoreOrderItem toItem(StoreOrderView o, Map<Long, UserProfileView> customers) {
        return StoreOrderItem.builder()
                .id(o.getId()).orderNo(o.getOrderNo()).appointmentId(o.getAppointmentId())
                .status(o.getStatus()).amount(o.getAmount()).paidAmount(o.getPaidAmount())
                .paymentMethod(o.getPaymentMethod()).payExpireAt(o.getPayExpireAt())
                .paidAt(o.getPaidAt()).createdAt(o.getCreatedAt())
                .serviceName(o.getServiceName()).durationMin(o.getDurationMin())
                .customer(OrderCustomer.of(customers.get(o.getUserId())))
                .build();
    }

    /** 门店订单行视图（金额=分，Long 经全局 Jackson 字符串化；customer 为 null 表示账号已删）。 */
    @Data
    @Builder
    public static class StoreOrderItem {
        private Long id;
        private String orderNo;
        private Long appointmentId;
        private String status;
        private Long amount;
        private Long paidAmount;
        private String paymentMethod;
        private LocalDateTime payExpireAt;
        private LocalDateTime paidAt;
        private LocalDateTime createdAt;
        private String serviceName;
        private Integer durationMin;
        private OrderCustomer customer;
        /** 线下收款状态（ADR-006 §6）：该单无线下流水时为 null；列表接口恒 null，仅详情返回 */
        private OfflinePaymentInfo offlinePayment;
    }

    /** 线下收款登记入参：备注选填，列宽 VARCHAR(200)。 */
    @Data
    public static class OfflinePaymentRegisterRequest {
        @Size(max = 200, message = "登记备注不能超过200字")
        private String note;
    }

    /** 线下收款流水视图（金额=分；status=PROCESSING 待复核 / SUCCESS 已复核）。 */
    @Data
    @Builder
    public static class OfflinePaymentInfo {
        private String paymentNo;
        private Long amount;
        private String status;
        private Long registeredBy;
        private String registrantName;
        private String note;
        private Long confirmedBy;
        private LocalDateTime createdAt;
        private LocalDateTime paidAt;

        static OfflinePaymentInfo of(OfflinePaymentView v, String registrantName) {
            return OfflinePaymentInfo.builder()
                    .paymentNo(v.getPaymentNo()).amount(v.getAmount()).status(v.getStatus())
                    .registeredBy(v.getRegisteredBy()).registrantName(registrantName)
                    .note(v.getRegisteredNote()).confirmedBy(v.getConfirmedBy())
                    .createdAt(v.getCreatedAt()).paidAt(v.getPaidAt())
                    .build();
        }
    }

    /** 下单客户（ADR-017 §3：门店侧只见脱敏号，无明文查询入口）。 */
    @Data
    @Builder
    public static class OrderCustomer {
        private Long userId;
        private String nickname;
        private String phoneMasked;
        /** NORMAL/DISABLED */
        private String status;

        static OrderCustomer of(UserProfileView p) {
            if (p == null) {
                return null;
            }
            return OrderCustomer.builder()
                    .userId(p.getUserId()).nickname(p.getNickname())
                    .phoneMasked(maskPhone(p.getPhone())).status(p.getStatus())
                    .build();
        }

        /** {@code 13900004004 → 139****0004}；异常长度也只留前 3 位，绝不回退成明文。 */
        private static String maskPhone(String phone) {
            if (phone == null || phone.isBlank()) {
                return null;
            }
            String p = phone.trim();
            if (p.length() >= 7) {
                return p.substring(0, 3) + "****" + p.substring(p.length() - 4);
            }
            return p.substring(0, Math.min(3, p.length())) + "****";
        }
    }
}
