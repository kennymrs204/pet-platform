package com.petplatform.app.web;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.app.service.PlatformParamQuery;
import com.petplatform.order.api.RefundApprovalApi;
import com.petplatform.order.api.RefundApprovalApi.Approver;
import com.petplatform.order.api.RefundQueryApi;
import com.petplatform.order.api.dto.RefundView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * B 端退款审批（ADR-037）。两条权限码分工明确：
 * {@code order:refund:view}=全部员工可看退款进度（V4 新增），
 * {@code order:refund:approve}=店长/前台/平台管理员可批（V2 既有 + V17 补前台，三角色同权口径）。
 *
 * <p>为什么落在 app：审批要同时读 storeCtx、sys_config 507 限额与 order 域命令，
 * 而 order 模块既看不到门店上下文也不该依赖 app——只有装配根能同时握这三样
 * （{@code StoreOrderController}/{@code PermissionAssembler} 先例，矩阵零新增边）。
 *
 * <p><b>出参刻意不含 userId / 客户手机号</b>：任务角色（医生/美容师/遛狗/护理）拿到的是
 * {@code order:refund:view}，而 ADR-017 明确他们不该看到客户联系方式与消费额。
 * 所以这里只给"够审这一单"的字段；要看客户得另持 {@code customer:view:store}。
 *
 * <p>身份判定口径：{@code storeCtx==1}（平台总部虚拟店，ADR-001-A）即 ADMIN 上下文，
 * 跨店不限额；否则按店长处理——只能审本店退款单（跨店统一 10004，不回显存在性），
 * 且金额不得超过 sys_config 507 上限（超额 60006）。
 */
@RestController
@RequestMapping("/api/v1/store/refunds")
public class StoreRefundController {

    private static final long HQ_STORE_ID = 1L;
    private static final String LIMIT_KEY = "refund.auto_approve_limit_fen";
    /** 配置缺失/非数字时的兜底上限，与 V2 种子值一致（500 元） */
    private static final long DEFAULT_LIMIT_FEN = 50000L;
    private static final Set<String> REFUND_STATUSES = Set.of(
            "APPLYING", "APPROVED", "REJECTED", "EXECUTING", "SUCCESS", "FAILED");

    private final RefundQueryApi refundQueryApi;
    private final RefundApprovalApi refundApprovalApi;
    private final PlatformParamQuery platformParamQuery;

    public StoreRefundController(RefundQueryApi refundQueryApi, RefundApprovalApi refundApprovalApi,
                                 PlatformParamQuery platformParamQuery) {
        this.refundQueryApi = refundQueryApi;
        this.refundApprovalApi = refundApprovalApi;
        this.platformParamQuery = platformParamQuery;
    }

    /** 退款单列表：status=APPLYING 即"待处理退款"；不传=全部（含历史与已退成功）。 */
    @GetMapping
    @PreAuthorize("hasAuthority('perm:order:refund:view')")
    public Result<PageResult<RefundItem>> list(@RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "1") int pageNum,
                                               @RequestParam(defaultValue = "20") int pageSize) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeCtx = requireStoreCtx(principal);
        requireStatus(status);
        PageResult<RefundView> page = refundQueryApi.page(
                blankToNull(status), adminCtx(storeCtx) ? null : storeCtx, pageNum, pageSize);
        List<RefundItem> items = page.getList().stream().map(RefundItem::of).toList();
        return Result.ok(PageResult.of(page.getTotal(), page.getPageNum(), page.getPageSize(), items));
    }

    /** 通过并（模拟）执行退款。幂等由退款单 status 的 CAS 兜住：重复批第二次得 60005。 */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('perm:order:refund:approve')")
    public Result<RefundItem> approve(@PathVariable Long id) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeCtx = requireStoreCtx(principal);
        boolean admin = adminCtx(storeCtx);
        long limitFen = admin ? 0L : platformParamQuery.longValue(LIMIT_KEY, DEFAULT_LIMIT_FEN);
        return Result.ok(RefundItem.of(refundApprovalApi.approve(id, approver(principal, storeCtx, admin), limitFen)));
    }

    /** 拒绝退款申请：订单回 PAID，预约恢复 CONFIRMED 可继续服务（ADR-043）。限额闸对拒绝同样生效。 */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('perm:order:refund:approve')")
    public Result<RefundItem> reject(@PathVariable Long id, @Valid @RequestBody RejectRequest body) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeCtx = requireStoreCtx(principal);
        boolean admin = adminCtx(storeCtx);
        long limitFen = admin ? 0L : platformParamQuery.longValue(LIMIT_KEY, DEFAULT_LIMIT_FEN);
        return Result.ok(RefundItem.of(
                refundApprovalApi.reject(id, approver(principal, storeCtx, admin), limitFen, body.getRejectReason())));
    }

    /** 拒绝请求体：理由必填（服务端再校 1~200，这里 @NotBlank 挡空串）。 */
    @Data
    public static class RejectRequest {
        @NotBlank(message = "拒绝理由不能为空")
        @Size(max = 200, message = "拒绝理由不能超过 200 字")
        private String rejectReason;
    }

    private static Approver approver(AuthPrincipal principal, Long storeCtx, boolean admin) {
        return new Approver(principal.userId(), principal.employeeId(), storeCtx, admin);
    }

    private static boolean adminCtx(Long storeCtx) {
        return HQ_STORE_ID == storeCtx;
    }

    private static Long requireStoreCtx(AuthPrincipal principal) {
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }

    private static void requireStatus(String status) {
        if (status != null && !status.isBlank() && !REFUND_STATUSES.contains(status)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "退款单状态取值非法");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** 退款单行视图（金额=分，Long 经全局 Jackson 字符串化）。 */
    @Data
    @Builder
    public static class RefundItem {
        private Long id;
        private String refundNo;
        private Long orderId;
        private Long appointmentId;
        private Long storeId;
        private Long amount;
        private String reason;
        /** 门店拒绝理由；仅 REJECTED 有值（V17） */
        private String rejectReason;
        /** APPLYING=待处理 / SUCCESS=已退款（本期模拟）/ REJECTED=已拒绝 */
        private String status;
        /** USER/STORE/ADMIN/SYSTEM */
        private String applyBy;
        private String approveRole;
        private LocalDateTime createdAt;
        private LocalDateTime refundedAt;

        static RefundItem of(RefundView r) {
            if (r == null) {
                return null;
            }
            return RefundItem.builder()
                    .id(r.getId()).refundNo(r.getRefundNo()).orderId(r.getOrderId())
                    .appointmentId(r.getAppointmentId()).storeId(r.getStoreId())
                    .amount(r.getAmount()).reason(r.getReason()).rejectReason(r.getRejectReason())
                    .status(r.getStatus())
                    .applyBy(r.getApplyBy()).approveRole(r.getApproveRole())
                    .createdAt(r.getCreatedAt()).refundedAt(r.getRefundedAt())
                    .build();
        }
    }
}
