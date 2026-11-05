package com.petplatform.appointment.web;

import com.petplatform.appointment.api.dto.AppointmentCheckinView;
import com.petplatform.appointment.service.AppointmentCheckinService;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.CurrentUserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店侧「到店签到」（ADR-042，P8 §4.3 :218 的 CHECKIN 动作）。
 *
 * <p><b>两处对 P8 原契约的偏离，理由记在这里</b>：
 * <ol>
 *   <li>权限码用 {@code appointment:checkin} 而不是 §3.4 写的 {@code appointment:advance}。
 *       实测 V2 种子：{@code checkin} 只有 STORE_MANAGER 与 RECEPTIONIST 持有，而 {@code advance}
 *       美容师/医生/遛狗师/护理员全都有——签到是前台动作，用 advance 等于让美容师能给顾客签到。
 *       取更窄的既有专码，零权限迁移。</li>
 *   <li>不做 §3.4 的 {@code POST /appointments/{id}/status {action:CHECKIN|CANCEL}} 合并端点，
 *       落成动作专路径 {@code POST /appointments/{id}/checkin}（与已上线的
 *       {@code POST /store/boarding-stays/{id}/checkin} 同形）。CANCEL 要牵退款政策链（ADR-023 在 C 端），
 *       只上半个枚举就得留一条恒抛"未实现"的分支——那是半截契约；CANCEL 落地时另开 {@code /cancel}。</li>
 * </ol>
 *
 * <p>无请求体：签到没有可填字段（库里也没有"到店时间"列），有朝一日要记到店时间应加列而不是塞 body。
 */
@RestController
@RequestMapping("/api/v1/store/appointments")
@RequiredArgsConstructor
public class StoreAppointmentCheckinController {

    private final AppointmentCheckinService checkinService;

    @PostMapping("/{id}/checkin")
    @PreAuthorize("hasAuthority('perm:appointment:checkin')")
    public Result<AppointmentCheckinView> checkin(@PathVariable Long id) {
        return Result.ok(checkinService.checkin(CurrentUserHolder.require(), id));
    }
}
