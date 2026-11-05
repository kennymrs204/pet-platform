package com.petplatform.app.web;

import com.petplatform.app.service.TaskCompletionService;
import com.petplatform.appointment.api.AppointmentTaskApi;
import com.petplatform.appointment.api.dto.AppointmentTaskView;
import com.petplatform.appointment.api.dto.AssignEmployeeRequest;
import com.petplatform.appointment.api.dto.ChangeResourceRequest;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.fulfillment.api.dto.FinishServiceRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店侧「履约三动作」端点（ADR-048）：分配服务人员 → 开始服务 → 完成服务。
 *
 * <p><b>权限码全部取既有种子，零新增、零迁移</b>（实测 {@code emp_role_permission}）：
 * {@code appointment:assign}/{@code appointment:reassign} 只有 STORE_MANAGER/RECEPTIONIST/ADMIN 持有；
 * {@code task:start}/{@code task:finish} 各服务角色都有。这三枚码此前<b>全仓零引用</b>——
 * 种子在 ADR-001 阶段就埋好了，缺的从来不是权限，是端点。
 *
 * <p>"谁能操作哪一单"这件事在 Service 里判（{@code storeWide} 由权限码集合算出后传入），
 * 与 {@code GroomingBoardController} 同一口径：店长/前台代全店操作，服务人员只动派给自己的。
 *
 * <p>刻意<b>不做</b> {@code task:accept}：它是"我接单"的意思，而库里没有任何一列能表达
 * "已接单但还没开始"——加列就是新状态（§十七 零迁移优先）。现状是 accept 码继续只当
 * "看/动自己任务"的读侧闸（{@code /store/grooming/queue}、{@code /store/tasks/mine}）。
 *
 * <p>路径沿用 b-web 早已写在 {@code api/bathing.ts} 注释里的规划名（{@code assign-employee}），
 * 前端不需要再猜一次。
 */
@RestController
@RequestMapping("/api/v1/store/appointments")
public class StoreAppointmentTaskController {

    private final AppointmentTaskApi appointmentTaskApi;
    private final TaskCompletionService completionService;

    public StoreAppointmentTaskController(AppointmentTaskApi appointmentTaskApi,
                                          TaskCompletionService completionService) {
        this.appointmentTaskApi = appointmentTaskApi;
        this.completionService = completionService;
    }

    /** 分配/改派：首派要 {@code appointment:assign}，换人还要 {@code appointment:reassign}。 */
    @PutMapping("/{id}/assign-employee")
    @PreAuthorize("hasAuthority('perm:appointment:assign') or hasAuthority('perm:appointment:reassign')")
    public Result<AppointmentTaskView> assign(@PathVariable Long id,
                                              @Valid @RequestBody AssignEmployeeRequest request) {
        AuthPrincipal principal = CurrentUserHolder.require();
        boolean allowReassign = principal.permissions().contains("appointment:reassign");
        return Result.ok(appointmentTaskApi.assignEmployee(id, request.getEmployeeId(),
                principal.storeCtx(), allowReassign));
    }

    /** 换台位（ADR-048 §8 核销）：未开始才可换；冲突 40004 让门店重挑。同 assign 的权限口径——台位安排是店内调度动作。 */
    @PutMapping("/{id}/resource")
    @PreAuthorize("hasAuthority('perm:appointment:assign') or hasAuthority('perm:appointment:reassign')")
    public Result<AppointmentTaskView> changeResource(@PathVariable Long id,
                                                      @Valid @RequestBody ChangeResourceRequest request) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(appointmentTaskApi.changeResource(id, request.getResourceId(),
                principal.storeCtx()));
    }

    /** 开始服务：CONFIRMED|WAIT_SERVICE → SERVING，写 actual_start_at。 */
    @PostMapping("/{id}/start-service")
    @PreAuthorize("hasAuthority('perm:task:start')")
    public Result<AppointmentTaskView> start(@PathVariable Long id) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(appointmentTaskApi.startService(id, principal.storeCtx(), principal.employeeId(),
                principal.permissions().contains("appointment:view:store")));
    }

    /**
     * 完成服务：SERVING → COMPLETED，并在<b>同一事务</b>里写履约记录
     * （洗护→{@code ful_bath_record}，遛狗→{@code ful_walk_record}，医疗类本轮无表可写）。
     */
    @PostMapping("/{id}/finish-service")
    @PreAuthorize("hasAuthority('perm:task:finish')")
    public Result<AppointmentTaskView> finish(@PathVariable Long id,
                                              @RequestBody(required = false) FinishServiceRequest request) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(completionService.finish(id, principal,
                request == null ? null : request.getServiceItems(),
                request == null ? null : request.getRemark()));
    }
}
