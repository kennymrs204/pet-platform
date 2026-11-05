package com.petplatform.appointment.service;

import com.petplatform.appointment.api.AppointmentErrorCode;
import com.petplatform.appointment.api.AppointmentTaskApi;
import com.petplatform.appointment.api.dto.AppointmentTaskView;
import com.petplatform.appointment.domain.AppointmentEntity;
import com.petplatform.appointment.mapper.AppointmentCommandMapper;
import com.petplatform.catalog.api.CatalogQueryApi;
import com.petplatform.catalog.api.dto.StoreServiceView;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.schedule.api.ResourceErrorCode;
import com.petplatform.schedule.api.ResourceOccupancyApi;
import com.petplatform.schedule.api.ScheduleLockApi;
import com.petplatform.schedule.api.dto.KennelResourceView;
import com.petplatform.schedule.api.dto.ScheduleLockView;
import com.petplatform.store.api.StoreQueryApi;
import com.petplatform.store.api.dto.StoreView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 履约命令实现（ADR-048）：分配 / 开始服务 / 完成服务。先例是 {@link AppointmentCheckinService}——
 * <b>条件更新先到先得、幂等重放静默、非法迁移 50003</b>，不引入分布式锁（Redis 锁是为"创建要抢资源槽"
 * 而存在的，分配只动一行）。
 *
 * <p>三条硬规矩，都是审计从既有链里读出来的，不是新发明：
 * <ol>
 *   <li><b>托管单不进这条链</b>。它的 {@code SERVING/COMPLETED} 由 boarding 的
 *       {@code updateStartBoarding(WHERE status='CONFIRMED')} 在"办理入住/离店"时写，
 *       语义是入住而不是开始服务；两边抢同一个前置条件会互相打不动（ADR-042 同款教训）。</li>
 *   <li><b>人员合法性复用创建链的同一套判据</b>（{@link AvailabilityService#requireEmployeeBookable}：
 *       班次窗 + 技能超集 + 档期重叠 + buffer）。另写一份校验迟早和创建链分叉。
 *       唯一要避开的自撞：给<b>同一个员工</b>重复分配时，该单自己就在他的占用区间里——
 *       所以"目标员工==当前员工"先短路回幂等成功，根本不进校验。</li>
 *   <li><b>服务下架不挡分配</b>。这里读服务配置走 {@code storeServiceOf}（纯读）而不是
 *       {@code requireBookableService}（可约校验）——单已经付了钱，门店必须能把它做完，
 *       不能因为目录下架就把待履约的单锁死。</li>
 * </ol>
 */
@Slf4j
@Service
public class AppointmentTaskService implements AppointmentTaskApi {

    /** 可分配窗：已确认与待服务。一旦 SERVING，执行人就定了（§九.7）。 */
    private static final Set<String> ASSIGNABLE = Set.of("CONFIRMED", "WAIT_SERVICE");

    private final AppointmentCommandMapper mapper;
    private final AvailabilityService availability;
    private final ScheduleLockApi scheduleLockApi;
    private final ResourceOccupancyApi occupancyApi;
    private final CatalogQueryApi catalogApi;
    private final StoreQueryApi storeApi;
    private final EmployeeQueryApi employeeApi;
    private final org.springframework.context.ApplicationEventPublisher events;

    public AppointmentTaskService(AppointmentCommandMapper mapper, AvailabilityService availability,
                                  ScheduleLockApi scheduleLockApi, ResourceOccupancyApi occupancyApi,
                                  CatalogQueryApi catalogApi,
                                  StoreQueryApi storeApi, EmployeeQueryApi employeeApi,
                                  org.springframework.context.ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.availability = availability;
        this.scheduleLockApi = scheduleLockApi;
        this.occupancyApi = occupancyApi;
        this.catalogApi = catalogApi;
        this.storeApi = storeApi;
        this.employeeApi = employeeApi;
        this.events = events;
    }

    @Override
    @Transactional
    public AppointmentTaskView assignEmployee(Long appointmentId, Long employeeId, Long storeCtx,
                                              boolean allowReassign) {
        AppointmentEntity apt = loadInStore(appointmentId, storeCtx);
        if (employeeId == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "employeeId 必填");
        }
        requireNotBoarding(apt, "分配服务人员");
        String status = apt.getStatus();
        if ("PENDING_PAYMENT".equals(status)) {
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION, "预约尚未支付，不能分配服务人员");
        }
        if ("CANCELLED".equals(status) || "CANCEL_REQUESTED".equals(status)) {
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION,
                    "预约已取消或正在取消审核中，不能分配服务人员");
        }
        if (!ASSIGNABLE.contains(status)) {
            // 窄口径补派（2026-10-27 履约轮）："开始不要求已指派"会留下 SERVING 且无执行人的单——
            // 完成被 ful_*_record.employee_id NOT NULL 拒、改派被本闸拒，两头堵死。
            // 放行仅限"从无人 → 有人"的首派（履约记录还没写，不存在"谁做的"失据）；
            // 已有执行人再动仍是改派，照旧 50004。
            if (!"SERVING".equals(status) || apt.getEmployeeId() != null) {
                throw new BusinessException(AppointmentErrorCode.SERVICE_IN_PROGRESS, "服务已开始，不可改派");
            }
            log.info("SERVING 无执行人单窄口径补派 appointmentId={} employeeId={}", apt.getId(), employeeId);
        }
        if (Objects.equals(apt.getEmployeeId(), employeeId)) {
            // 幂等：同一个人重复分配不校验、不写库、不 +version——否则这条单会和自己撞档期
            return view(apt, employeeId, false);
        }
        if (apt.getEmployeeId() != null && !allowReassign) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN,
                    "该单已有执行人，改派需要 appointment:reassign 权限");
        }
        if (!employeeApi.isActiveInStore(employeeId, apt.getStoreId())) {
            throw new BusinessException(AppointmentErrorCode.EMPLOYEE_NOT_WORKING, "员工非该店在职");
        }
        StoreServiceView svc = catalogApi.storeServiceOf(apt.getStoreServiceId());
        if (svc == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "该预约的服务配置已不可读，无法校验人员技能");
        }
        StoreView store = storeApi.store(apt.getStoreId());
        if (store == null || apt.getStartTime() == null || apt.getEndTime() == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "门店或该单的时段信息不完整，无法校验班次");
        }
        ScheduleLockView shift = scheduleLockApi.lockScheduleForCreate(employeeId, apt.getServiceDate());
        availability.requireEmployeeBookable(shift, store, apt.getServiceDate(),
                AvailabilityService.slotOf(apt.getStartTime()), AvailabilityService.slotOf(apt.getEndTime()),
                AvailabilityService.requiredSkillIds(svc), employeeId);
        if (mapper.updateAssign(apt.getId(), employeeId, apt.getVersion()) == 0) {
            AppointmentEntity now = mapper.selectById(apt.getId());
            if (now != null && Objects.equals(now.getEmployeeId(), employeeId)) {
                return view(now, employeeId, false);   // 并发：同事已把同一人派上，算成功
            }
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION,
                    now == null ? "预约不存在，分配中止" : "预约已被他人改动或状态已推进，请刷新后重试");
        }
        AppointmentEntity after = mapper.selectById(apt.getId());
        log.info("分配服务人员 appointmentId={} employeeId={} storeCtx={}", apt.getId(), employeeId, storeCtx);
        // ADR-052/053：指派落定（changed=true）→ AFTER_COMMIT 双通知——新执行人"来活了"；
        // 改派时原执行人（apt 载入时的执行人，即 CAS 前事实）收"任务已调整"。新旧同一人则旧通知不发。
        events.publishEvent(new com.petplatform.appointment.api.event.AppointmentAssignedEvent(
                apt.getId(), apt.getStoreId(), employeeId, apt.getEmployeeId(),
                after != null ? after.getServiceNameSnapshot() : apt.getServiceNameSnapshot(),
                apt.getServiceDate(), apt.getStartTime(),
                java.time.LocalDateTime.now()));
        return view(after == null ? apt : after, employeeId, true);
    }

    @Override
    @Transactional
    public AppointmentTaskView startService(Long appointmentId, Long storeCtx, Long operatorEmployeeId,
                                            boolean storeWide) {
        AppointmentEntity apt = loadInStore(appointmentId, storeCtx);
        requireNotBoarding(apt, "开始服务");
        requireOperable(apt, operatorEmployeeId, storeWide);
        // 2026-10-27 履约轮（ADR-054 修订）："开始"不再放行未指派单——否则 SERVING 无执行人的单
        // 完成被 ful_*_record.employee_id NOT NULL 拒、又不能改派，两头堵死（花卷单实锤）。
        // 与"未指派单对服务人员也拒"同哲学：宁可让门店先分配，也不写一个假名字。
        if (apt.getEmployeeId() == null) {
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION,
                    "该单尚未指派执行人，请先指派再开始服务");
        }
        String status = apt.getStatus();
        if ("PENDING_PAYMENT".equals(status)) {
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION, "预约尚未支付，不能开始服务");
        }
        if ("CANCELLED".equals(status) || "CANCEL_REQUESTED".equals(status)) {
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION, "预约已取消或审核中，不能开始服务");
        }
        if (!"CONFIRMED".equals(status) && !"WAIT_SERVICE".equals(status)) {
            return view(apt, apt.getEmployeeId(), false);   // 已是 SERVING/COMPLETED：幂等
        }
        // ADR-054：开始可以提前（软认领，不产生履约事实——actual_start_at 诚实地记录真实时刻），
        // 但完成（finish）有独立日期闸：服务日期未到不允许写完成记录（假完成=假履约事实）。
        if (mapper.updateStartService(apt.getId()) == 0) {
            AppointmentEntity now = mapper.selectById(apt.getId());
            String latest = now == null ? null : now.getStatus();
            if ("SERVING".equals(latest) || "COMPLETED".equals(latest)) {
                return view(now, now.getEmployeeId(), false);   // 并发竞争失败方：对方已推进，同样算成功
            }
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION, "预约状态已变更，请刷新后重试");
        }
        AppointmentEntity after = mapper.selectById(apt.getId());
        log.info("开始服务 appointmentId={} employeeId={}", apt.getId(), apt.getEmployeeId());
        return view(after == null ? apt : after, apt.getEmployeeId(), true);
    }

    @Override
    @Transactional
    public AppointmentTaskView finishService(Long appointmentId, Long storeCtx, Long operatorEmployeeId,
                                             boolean storeWide) {
        AppointmentEntity apt = loadInStore(appointmentId, storeCtx);
        requireNotBoarding(apt, "完成服务");
        requireOperable(apt, operatorEmployeeId, storeWide);
        String status = apt.getStatus();
        if ("COMPLETED".equals(status)) {
            return view(apt, apt.getEmployeeId(), false);   // 重复完成：幂等
        }
        if (!"SERVING".equals(status)) {
            // CONFIRMED/WAIT_SERVICE 也要先点"开始服务"：跳过开始会把 actual_start_at 留空，
            // 等于凭空造出一条"没有起点"的完成事实，履约时长就成了假数据。
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION,
                    "该单尚未开始服务，请先执行「开始服务」");
        }
        // ADR-054：完成不允许**大幅提前**——服务日期比今天晚超过 1 天时，"做完了"就是假履约事实
        // （ful_*_record 的实起止会落到预约日期之前数天）。宽 1 天是容忍"前晚遛明早的狗"这类
        // 真实提前履约；多天提前（如 10-02 完成 10-06 的单，用户实测踩中）被阻断。
        if (apt.getServiceDate() != null && apt.getServiceDate().isAfter(com.petplatform.common.time.BusinessDates.today().plusDays(1))) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID,
                    "该单预约日期是 " + apt.getServiceDate() + "，距离预约日期尚远，不能提前完成任务");
        }
        if (mapper.updateFinishService(apt.getId()) == 0) {
            AppointmentEntity now = mapper.selectById(apt.getId());
            if (now != null && "COMPLETED".equals(now.getStatus())) {
                return view(now, now.getEmployeeId(), false);
            }
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION, "预约状态已变更，请刷新后重试");
        }
        AppointmentEntity after = mapper.selectById(apt.getId());
        AppointmentEntity fact = after == null ? apt : after;
        log.info("完成服务 appointmentId={} employeeId={}", apt.getId(), apt.getEmployeeId());
        // ADR-053：预约落到 COMPLETED（INSTANT 履约完成）→ AFTER_COMMIT 给顾客发"服务已完成/请接走"
        events.publishEvent(new com.petplatform.appointment.api.event.AppointmentCompletedEvent(
                apt.getId(), apt.getUserId(), apt.getStoreId(), fact.getPetId(),
                fact.getServiceNameSnapshot(),
                com.petplatform.appointment.api.event.AppointmentCompletedEvent.SOURCE_INSTANT_FINISH,
                fact.getActualStartAt(), fact.getActualEndAt(),
                java.time.LocalDateTime.now()));
        return view(fact, apt.getEmployeeId(), true);
    }

    @Override
    @Transactional
    public AppointmentTaskView changeResource(Long appointmentId, Long targetResourceId, Long storeCtx) {
        AppointmentEntity apt = loadInStore(appointmentId, storeCtx);
        if (targetResourceId == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "resourceId 必填");
        }
        requireNotBoarding(apt, "换台位");
        String status = apt.getStatus();
        if ("PENDING_PAYMENT".equals(status)) {
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION, "预约尚未支付，不能换台位");
        }
        if ("CANCELLED".equals(status) || "CANCEL_REQUESTED".equals(status)) {
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION,
                    "预约已取消或正在取消审核中，不能换台位");
        }
        if (!ASSIGNABLE.contains(status)) {
            // SERVING/COMPLETED：占用行已随服务开始沉淀为履约事实的一部分，挪走会让记录与台位对不上
            throw new BusinessException(AppointmentErrorCode.SERVICE_IN_PROGRESS, "服务已开始，不可换台位");
        }
        if (apt.getServiceDate() == null || apt.getStartTime() == null || apt.getEndTime() == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "该单的时段信息不完整，无法换台位");
        }
        // 幂等：目标==当前占用台位，不释放重占——否则这条单会和自己撞 UNIQUE(resource,date,slot)
        var current = occupancyApi.timeSlotOccupancyOfAppointment(apt.getId(), apt.getServiceDate());
        if (current.containsKey(targetResourceId)) {
            return view(apt, apt.getEmployeeId(), false);
        }
        StoreServiceView svc = catalogApi.storeServiceOf(apt.getStoreServiceId());
        if (svc == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "该预约的服务配置已不可读，无法校验台位类型");
        }
        String requiredType = svc.getRequiredResourceType();
        if (requiredType == null || requiredType.isBlank()) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "该服务不使用台位，无需换台位");
        }
        // 先纯读校验目标（跨店/不存在 → null），再锁行复检——与改期链同款两段式
        KennelResourceView target = occupancyApi.resource(apt.getStoreId(), targetResourceId);
        if (target == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        if (!requiredType.equals(target.getType())) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID,
                    "目标台位类型与该服务所需不符（需要 " + requiredType + "）");
        }
        if (!"AVAILABLE".equals(target.getStatus())) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "目标台位当前不可用（维护中或已停用）");
        }
        int fromSlot = AvailabilityService.slotOf(apt.getStartTime());
        int toSlot = AvailabilityService.slotOf(apt.getEndTime());
        if (toSlot <= fromSlot) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "该单的时段信息不完整，无法换台位");
        }
        // 锁序"资源行 → 占用行"（与停用/创建/改期一致）；锁定后重检 AVAILABLE 与他人占用
        KennelResourceView locked = occupancyApi.lockResourceForUse(apt.getStoreId(), targetResourceId);
        if (locked == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        if (!"AVAILABLE".equals(locked.getStatus())) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "目标台位当前不可用（维护中或已停用）");
        }
        if (occupancyApi.hasOtherTimeSlotOccupancyForUpdate(targetResourceId,
                apt.getServiceDate(), fromSlot, toSlot, apt.getId())) {
            // 拍板口径：报错让门店重挑，不自动换第二选择
            throw new BusinessException(ResourceErrorCode.RESOURCE_FULL, "目标台位该时段已被占用，请重挑");
        }
        occupancyApi.releaseAll(apt.getId());
        List<Integer> slots = new ArrayList<>();
        for (int s = fromSlot; s < toSlot; s++) {
            slots.add(s);
        }
        occupancyApi.occupyTimeSlots(targetResourceId, apt.getId(), apt.getServiceDate(), slots);
        log.info("换台位 appointmentId={} targetResourceId={} storeCtx={}", apt.getId(), targetResourceId, storeCtx);
        // 零预约列写、零事件：占用行本身就是全部事实（ADR-048 §7 无此事件类型，ADR-038 台位真相只在占用条）
        return view(apt, apt.getEmployeeId(), true);
    }

    /**
     * 只读任务视图（不推进状态）：给履约记录的附加操作（如补照片）做授权前置。
     * 判据与 start/finish 完全同源——门店隔离走 {@link #loadInStore}，人员走 {@link #requireOperable}。
     */
    @Override
    public AppointmentTaskView taskOf(Long appointmentId, Long storeCtx, Long operatorEmployeeId,
                                      boolean storeWide) {
        AppointmentEntity apt = loadInStore(appointmentId, storeCtx);
        requireOperable(apt, operatorEmployeeId, storeWide);
        return view(apt, apt.getEmployeeId(), false);
    }

    /** 门店隔离：跨店与不存在同口径 10004（不透露"这单在别的店"，同 ADR-015/042 口径）。 */
    private AppointmentEntity loadInStore(Long appointmentId, Long storeCtx) {
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        if (appointmentId == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "appointmentId 必填");
        }
        AppointmentEntity apt = mapper.selectById(appointmentId);
        if (apt == null || !storeCtx.equals(apt.getStoreId())) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        return apt;
    }

    private void requireNotBoarding(AppointmentEntity apt, String action) {
        if (apt.getBoardingCheckinDate() != null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID,
                    "跨天托管单的" + action + "请到「托管服务」页办理入住/离店（会同时分配笼位与护理员）");
        }
    }

    /**
     * 谁有权点这一枪（§七.C）：持 {@code appointment:view:store} 的店长/前台/ADMIN 可代全店操作；
     * 只有 {@code task:*} 的服务人员，必须动<b>派给自己</b>的单。
     *
     * <p>"还没派人"的单对服务人员也拒（不是放行）：否则履约记录会挂到一个猜出来的执行人身上，
     * 而 {@code ful_*_record.employee_id} 是 NOT NULL——宁可让门店先分配，也不写一个假名字。
     */
    private void requireOperable(AppointmentEntity apt, Long operatorEmployeeId, boolean storeWide) {
        if (storeWide) {
            return;
        }
        if (operatorEmployeeId == null || operatorEmployeeId == 0L) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "该账号无员工身份，只能由店长/前台操作");
        }
        if (!operatorEmployeeId.equals(apt.getEmployeeId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "只能操作派给自己的任务");
        }
    }

    private static AppointmentTaskView view(AppointmentEntity apt, Long employeeId, boolean changed) {
        LocalDate date = apt.getServiceDate();
        return AppointmentTaskView.builder()
                .appointmentId(apt.getId())
                .appointmentNo(apt.getAppointmentNo())
                .status(apt.getStatus())
                .changed(changed)
                .storeId(apt.getStoreId())
                .petId(apt.getPetId())
                .storeServiceId(apt.getStoreServiceId())
                .employeeId(employeeId)
                .serviceDate(date)
                .startTime(apt.getStartTime())
                .endTime(apt.getEndTime())
                .actualStartAt(apt.getActualStartAt())
                .actualEndAt(apt.getActualEndAt())
                .build();
    }
}
