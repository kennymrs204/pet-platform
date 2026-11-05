package com.petplatform.app.web;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.order.api.OrderQueryApi;
import com.petplatform.order.api.RefundQueryApi;
import com.petplatform.order.api.dto.OrderView;
import com.petplatform.order.api.dto.RefundView;
import lombok.Builder;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * C 端退款进度（ADR-037 §3）。客户取消已支付预约后，取消响应里的 {@code refundState} 只是**当场那一次**的
 * 状态；审批发生在之后，所以必须有一个可反复查的入口，否则用户刷新后就只剩"已取消"，
 * 钱到底退没退完全不知道（这正是 ADR-023 记在 B7 的欠账）。
 *
 * <p>归属：appointment→order 后校 {@code order.user_id==本人}，不符统一 10004——
 * 与 ADR-014/023 一致，不回显"这条预约存在但不是你的"。
 *
 * <p>取单口径：优先返回**未被拒的最新一笔**（用户关心"我这笔退到哪了"），
 * 全被拒时返回最新一笔，让"被拒"这件事也可见。
 */
@RestController
@RequestMapping("/api/v1/customer/refunds")
public class CustomerRefundController {

    private final OrderQueryApi orderQueryApi;
    private final RefundQueryApi refundQueryApi;

    public CustomerRefundController(OrderQueryApi orderQueryApi, RefundQueryApi refundQueryApi) {
        this.orderQueryApi = orderQueryApi;
        this.refundQueryApi = refundQueryApi;
    }

    /** 该预约对应订单的退款进度；无退款单（未支付取消/尚未申请）返回 data=null。 */
    @GetMapping
    @PreAuthorize("hasAuthority('perm:order:view:self')")
    public Result<RefundProgress> progress(@RequestParam Long appointmentId) {
        AuthPrincipal principal = CurrentUserHolder.require();
        OrderView order = orderQueryApi.byAppointment(appointmentId);
        if (order == null || !principal.userId().equals(order.getUserId())) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        List<RefundView> refunds = refundQueryApi.byOrder(order.getId());
        RefundView latest = refunds.stream()
                .filter(r -> !"REJECTED".equals(r.getStatus()))
                .findFirst()
                .orElse(refunds.isEmpty() ? null : refunds.get(0));
        return Result.ok(latest == null ? null : RefundProgress.of(latest));
    }

    /** 客户可见的退款进度（金额=分；不含审批人身份，只给"谁批的"角色）。 */
    @Data
    @Builder
    public static class RefundProgress {
        private Long refundId;
        private String refundNo;
        private Long orderId;
        private Long appointmentId;
        /** APPLYING=审核中 / SUCCESS=退款成功 / REJECTED=未通过 */
        private String status;
        private Long amount;
        private String reason;
        /** 门店拒绝理由；仅 REJECTED 有值（V17），存量 NULL 由前端回退文案 */
        private String rejectReason;
        private LocalDateTime createdAt;
        private LocalDateTime refundedAt;

        static RefundProgress of(RefundView r) {
            return RefundProgress.builder()
                    .refundId(r.getId()).refundNo(r.getRefundNo())
                    .orderId(r.getOrderId()).appointmentId(r.getAppointmentId())
                    .status(r.getStatus()).amount(r.getAmount()).reason(r.getReason())
                    .rejectReason(r.getRejectReason())
                    .createdAt(r.getCreatedAt()).refundedAt(r.getRefundedAt())
                    .build();
        }
    }
}
