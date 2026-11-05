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
 * B 端消息中心（ADR-047）：门店收件箱。与 C 端 {@link CustomerNotificationController} 是
 * <b>同一批端口、同一个收件人不变量，多一条门店过滤</b>——通知模块仍没有 web 包（矩阵零新增边）。
 *
 * <p>为什么不直接让 B 端复用 C 端那四个 URL：实测店长 token 打
 * {@code GET /api/v1/customer/notifications} 确实返回 {@code code 0}（{@code ntf:receive} 八个角色全有），
 * 所以"能不能读"从来不是问题，问题是<b>语义</b>——那条链按人不分店，而
 * {@code /auth/employee-identities} 实测 DemoManager 同属 9111 与 9112，切店红点不会跟着店走；
 * 个人消息和门店提醒也会糊进同一个数字。另开 {@code /store/**} 才有地方安放 {@code store_id} 过滤。
 *
 * <p>安全不变量（照抄 ADR-041 §3）：<b>收件人和门店都只从 token 取</b>——
 * 端点签名里没有 userId，也没有 storeId 参数，{@code storeCtx} 来自
 * {@link CurrentUserHolder}，因此既不存在"改参数读别人的消息"，也不存在"改个头读别店的收件箱"。
 * 带非本人/非本店上下文打进来时，权限装配给出空码集 → 方法级 10003，读不到任何行。
 *
 * <p>{@code storeCtx=0}（C 端 token 打本控制器）由 service 兜成空盒，不会退化成"查全部"。
 */
@RestController
@RequestMapping("/api/v1/store/notifications")
public class StoreNotificationController {

    private final NotificationQueryApi notificationQueryApi;
    private final NotificationCommandApi notificationCommandApi;

    public StoreNotificationController(NotificationQueryApi notificationQueryApi,
                                       NotificationCommandApi notificationCommandApi) {
        this.notificationQueryApi = notificationQueryApi;
        this.notificationCommandApi = notificationCommandApi;
    }

    /** 本店上下文里发给我的消息，新→旧。 */
    @GetMapping
    @PreAuthorize("hasAuthority('perm:ntf:receive')")
    public Result<PageResult<NotificationView>> list(@RequestParam(defaultValue = "1") int pageNum,
                                                    @RequestParam(defaultValue = "20") int pageSize) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(notificationQueryApi.pageForUserInStore(
                principal.userId(), principal.storeCtx(), pageNum, pageSize));
    }

    /** 本店未读数（顶栏铃铛）。 */
    @GetMapping("/unread-count")
    @PreAuthorize("hasAuthority('perm:ntf:receive')")
    public Result<Long> unreadCount() {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(notificationQueryApi.unreadCountInStore(principal.userId(), principal.storeCtx()));
    }

    /** 单条已读：幂等；收件人与当前门店都只从认证上下文取，跨店 id 不能命中。 */
    @PostMapping("/{id}/read")
    @PreAuthorize("hasAuthority('perm:ntf:receive')")
    public Result<Void> markRead(@PathVariable Long id) {
        AuthPrincipal principal = CurrentUserHolder.require();
        notificationCommandApi.markReadInStore(principal.userId(), principal.storeCtx(), id);
        return Result.ok();
    }

    /** 本店全部已读，返回本次实际变更条数；不会波及别店与个人消息。 */
    @PostMapping("/read-all")
    @PreAuthorize("hasAuthority('perm:ntf:receive')")
    public Result<Long> markAllRead() {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok((long) notificationCommandApi.markAllReadInStore(
                principal.userId(), principal.storeCtx()));
    }
}
