package com.petplatform.app.web;

import com.petplatform.common.result.PageResult;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.notification.api.NotificationCommandApi;
import com.petplatform.notification.api.NotificationQueryApi;
import com.petplatform.notification.api.dto.NotificationView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端消息中心（ADR-041）：把已经落库的 ntf_notification 真正给到用户。
 *
 * <p><b>没有新增权限码，也没有新增迁移</b>：CUSTOMER 角色在 V2 早就有 {@code ntf:receive}
 * （第一阶段能力矩阵 §3.12），只是此前"有码无接口"。本类是那个码的第一个消费者。
 *
 * <p>安全不变量：<b>收件人只从 token 取</b>（{@code CurrentUserHolder.require().userId()}），
 * 端点签名里没有任何 userId/收件人参数，因此不存在"改参数读别人消息"的面。
 * <b>个人收件箱只含 {@code store_id = 0}</b>：同一账号在 B 端收到的门店通知不会混入。
 * 单条已读的归属校验在 {@link NotificationCommandApi#markReadInPersonalInbox} 内，
 * 不匹配统一 10004（不用 10003，免泄露"这条消息是否存在"）。
 *
 * <p>为什么落在 app 而不像订单那样落在业务模块：本类只做"当前用户 + notification 域"的薄装配，
 * 而 C 端其余控制器（CustomerOrderController/CustomerRefundController）分别在 order/app 里；
 * 消息属于 notification，通知模块没有 web 包（矩阵里它只发事件不接 HTTP），
 * 把 HTTP 装配放这里，零矩阵改动。
 */
@RestController
@RequestMapping("/api/v1/customer/notifications")
public class CustomerNotificationController {

    private final NotificationQueryApi notificationQueryApi;
    private final NotificationCommandApi notificationCommandApi;

    public CustomerNotificationController(NotificationQueryApi notificationQueryApi,
                                          NotificationCommandApi notificationCommandApi) {
        this.notificationQueryApi = notificationQueryApi;
        this.notificationCommandApi = notificationCommandApi;
    }

    /** 我的消息，新→旧。默认 20/页，上限见 {@link NotificationQueryApi#MAX_PAGE_SIZE}。 */
    @GetMapping
    @PreAuthorize("hasAuthority('perm:ntf:receive')")
    public Result<PageResult<NotificationView>> list(@RequestParam(defaultValue = "1") int pageNum,
                                                     @RequestParam(defaultValue = "20") int pageSize) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(notificationQueryApi.pageForPersonalInbox(principal.userId(), pageNum, pageSize));
    }

    /** 未读数（铃铛红点）。Long 经全局 Jackson 字符串化，前端 Number() 处理。 */
    @GetMapping("/unread-count")
    @PreAuthorize("hasAuthority('perm:ntf:receive')")
    public Result<Long> unreadCount() {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(notificationQueryApi.unreadCountForPersonalInbox(principal.userId()));
    }

    /** 单条已读：幂等，重复调用仍返回 0。 */
    @PostMapping("/{id}/read")
    @PreAuthorize("hasAuthority('perm:ntf:receive')")
    public Result<Void> markRead(@PathVariable Long id) {
        AuthPrincipal principal = CurrentUserHolder.require();
        notificationCommandApi.markReadInPersonalInbox(principal.userId(), id);
        return Result.ok();
    }

    /** 全部已读，返回本次实际变更条数（重复调用返回 0）。 */
    @PostMapping("/read-all")
    @PreAuthorize("hasAuthority('perm:ntf:receive')")
    public Result<Long> markAllRead() {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok((long) notificationCommandApi.markAllReadInPersonalInbox(principal.userId()));
    }
}
