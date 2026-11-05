package com.petplatform.order.web;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.order.api.OrderErrorCode;
import com.petplatform.order.api.OrderQueryApi;
import com.petplatform.order.api.dto.OrderView;
import com.petplatform.payment.api.PaymentCommandApi;
import com.petplatform.payment.api.dto.PrepayView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端订单（第八阶段 §4.2 /customer/orders**，ADR-016）。
 * 归属校验在 Service 层显式做（订单 userId == 当前登录人），非本人一律 10004 不泄露存在性。
 */
@RestController
@RequestMapping("/api/v1/customer/orders")
@Validated
public class CustomerOrderController {

    private final OrderQueryApi orderQueryApi;
    private final PaymentCommandApi paymentCommandApi;

    public CustomerOrderController(OrderQueryApi orderQueryApi, PaymentCommandApi paymentCommandApi) {
        this.orderQueryApi = orderQueryApi;
        this.paymentCommandApi = paymentCommandApi;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('perm:order:view:self')")
    public Result<PageResult<OrderView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从1开始") int pageNum,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "每页至少1条")
            @Max(value = 100, message = "每页最多100条") int pageSize) {
        return Result.ok(orderQueryApi.pageForUser(currentUserId(), status, pageNum, pageSize));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('perm:order:view:self')")
    public Result<OrderView> detail(@PathVariable Long id) {
        return Result.ok(requireOwned(id));
    }

    /** 重新拉起支付（§4.2 POST /orders/{id}/pay）：仅本人 WAIT_PAY 单；复用/新建 PROCESSING 流水。 */
    @PostMapping("/{id}/pay")
    @PreAuthorize("hasAuthority('perm:order:pay')")
    public Result<PrepayView> pay(@PathVariable Long id) {
        OrderView order = requireOwned(id);
        if (!"WAIT_PAY".equals(order.getStatus())) {
            throw new BusinessException(OrderErrorCode.NOT_PAYABLE);
        }
        String subject = String.valueOf(order.getItemSnapshot().getOrDefault("serviceName", "宠物服务"));
        return Result.ok(paymentCommandApi.createPrepay(order.getId(), order.getAmount(), subject));
    }

    private OrderView requireOwned(Long id) {
        OrderView order = orderQueryApi.get(id);
        if (order == null || !order.getUserId().equals(currentUserId())) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        return order;
    }

    private Long currentUserId() {
        AuthPrincipal principal = CurrentUserHolder.require();
        return principal.userId();
    }
}
