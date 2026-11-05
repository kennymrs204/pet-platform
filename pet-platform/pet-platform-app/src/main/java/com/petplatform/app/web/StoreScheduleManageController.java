package com.petplatform.app.web;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.appointment.api.AppointmentQueryApi;
import com.petplatform.schedule.api.ScheduleCommandApi;
import com.petplatform.schedule.api.dto.BatchGenerateCommand;
import com.petplatform.schedule.api.dto.BatchGenerateResult;
import com.petplatform.schedule.api.dto.ScheduleBrief;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;

/**
 * B 端排班写侧（ADR-034，P8 §4.3 :221）。读侧 {@code StoreScheduleController} 在 schedule 模块，
 * 两片同路径不同方法，Spring 按 method 区分不冲突。
 *
 * <p>控制器为什么必须在 app 层而不是 schedule 模块：改/删一条班之前要先问 appointment
 * "这天还有没有进行中的预约"，而依赖矩阵 §3.2 禁止 schedule → appointment（ArchUnit 固化）。
 * app 是唯一能同时读到两边的组合根——先例：ADR-024 §3 的 {@code StoreEmployeeController}、
 * ADR-033 的 {@code StoreEmployeeManageController}。
 *
 * <p>刻意<b>不</b>接 {@code PermissionAssembler.evict}，也<b>不</b>发事件：排班行不进权限码集合
 * （四表 JOIN 到 {@code emp_role_permission} 为止），改班不改变任何人的权限；而除预约侧外
 * 没有第二个消费者（对照 {@code StoreServiceOffShelfEvent} 至今无消费者的教训）。
 */
@RestController
@RequestMapping("/api/v1/store/schedules")
public class StoreScheduleManageController {

    private final ScheduleCommandApi scheduleCommandApi;
    private final AppointmentQueryApi appointmentQueryApi;

    public StoreScheduleManageController(ScheduleCommandApi scheduleCommandApi, AppointmentQueryApi appointmentQueryApi) {
        this.scheduleCommandApi = scheduleCommandApi;
        this.appointmentQueryApi = appointmentQueryApi;
    }

    /**
     * 批量排班。撞 {@code uk_emp_date} 的日子不触碰、在回执 skipped 里点名（含撞去哪家店），
     * 既不覆盖他店的班也不因一天冲突让整批失败——口径见 ADR-034 §2。
     */
    @PostMapping("/batch-generate")
    @PreAuthorize("hasAuthority('perm:schedule:manage')")
    public Result<BatchGenerateResult> batchGenerate(@Valid @RequestBody BatchGenerateCommand command) {
        return Result.ok(scheduleCommandApi.batchGenerate(requireStoreCtx(), command));
    }

    /** 只改时段：{@code status} 不在射程内（LEAVE 与请假审批属 P8 :222 那一片，店长要让人休息就删那天的班）。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('perm:schedule:manage')")
    public Result<ScheduleBrief> updateTime(@PathVariable Long id, @Valid @RequestBody TimeRangeRequest body) {
        Long storeId = requireStoreCtx();
        assertDayEditable(storeId, requireRow(storeId, id));
        ScheduleBrief updated = scheduleCommandApi.updateTime(storeId, id, body.getStartTime(), body.getEndTime());
        if (updated == null) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        return Result.ok(updated);
    }

    /** 物理删（sch_schedule 无 deleted 列，P2 约定）。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('perm:schedule:manage')")
    public Result<Void> delete(@PathVariable Long id) {
        Long storeId = requireStoreCtx();
        assertDayEditable(storeId, requireRow(storeId, id));
        if (scheduleCommandApi.delete(storeId, id) == 0) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        return Result.ok();
    }

    /* ---------- 内部 ---------- */

    /** 跨店与不存在同码 10004：不回显"这条班存在但属于别店"，防排班 id 探测（ADR-010 §4 同族）。 */
    private ScheduleBrief requireRow(Long storeId, Long id) {
        ScheduleBrief row = scheduleCommandApi.findInStore(storeId, id);
        if (row == null) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        return row;
    }

    /**
     * 那天还占着人就不许动班。预检落在控制器而不是服务里，是依赖矩阵逼出来的（类注释已述）。
     *
     * <p>粒度是"整天"而不是"被让出的那几个小时"：算重叠要把服务的 durationMin 也拉进来，
     * 而 {@code start_time} 对 MULTI_DAY 可空，判定会成一坨。店长先改派再调班，比静默把
     * 已确认的客人挂在一个没人服务的时段上便宜得多。
     */
    private void assertDayEditable(Long storeId, ScheduleBrief row) {
        long occupied = appointmentQueryApi.countScheduleOccupying(storeId, row.getEmployeeId(), row.getWorkDate());
        if (occupied > 0) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID,
                    "该员工 " + row.getWorkDate() + " 有 " + occupied + " 条进行中预约，请先改派或取消再调整排班");
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

    @Data
    public static class TimeRangeRequest {
        @NotNull(message = "startTime 必填")
        private LocalTime startTime;

        @NotNull(message = "endTime 必填")
        private LocalTime endTime;
    }
}
