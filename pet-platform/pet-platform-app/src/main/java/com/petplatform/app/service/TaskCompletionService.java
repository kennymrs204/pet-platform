package com.petplatform.app.service;

import com.petplatform.appointment.api.AppointmentTaskApi;
import com.petplatform.appointment.api.dto.AppointmentTaskView;
import com.petplatform.catalog.api.CatalogQueryApi;
import com.petplatform.catalog.api.dto.StoreServiceView;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.employee.api.dto.EmployeeBrief;
import com.petplatform.fulfillment.api.ServiceRecordCommandApi;
import com.petplatform.fulfillment.api.dto.BathRecordDraft;
import com.petplatform.fulfillment.api.dto.WalkRecordDraft;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * "完成服务"的跨模块编排（ADR-048 §10）：预约状态与履约记录<b>必须同生同死</b>。
 *
 * <p>为什么落在 app 装配层：完成一次服务要写两张不同 Owner 的表——
 * {@code apt_appointment}（appointment）与 {@code ful_bath_record}/{@code ful_walk_record}（fulfillment）。
 * 让 appointment 反向依赖 fulfillment 会破矩阵（ArchUnit 跨模块只经 api，且表 Owner 唯一），
 * 所以由组合根在一个本地事务里把两件事串起来：{@code finishService} 的 CAS 与记录 INSERT 同事务，
 * 任一步抛异常整体回滚 ⇒ 不会出现"预约已完成但没有记录"，也不会出现"有记录但预约还在服务中"。
 *
 * <p>判据沿用 ADR-038：<b>服务类型只看 {@code cat_service.code}，不看服务名快照</b>
 * （名字会被门店改，快照时长也会与目录不一致，实测踩过）。
 * 医疗类（PET_MEDICAL/VACCINATION）<b>两张表都装不下它</b>（没有 {@code ful_medical_record}），
 * 本轮如实只推进预约状态并 log 一条，不为了"看起来有记录"把诊疗塞进洗澡表，也不新建表
 * （§十七 零迁移优先，且那属于"确实需要新模型"的独立裁决）。
 */
@Slf4j
@Service
public class TaskCompletionService {

    private static final Set<String> BATH_CODES = Set.of("PET_BATH", "PET_GROOMING");
    private static final String CODE_WALK = "DOG_WALK";
    /** 执行人在本店没有角色时写这个哨兵值：它是"缺数据"的事实，不是编出来的角色名。 */
    private static final String NO_ROLE = "NO_ROLE";

    private final AppointmentTaskApi appointmentTaskApi;
    private final ServiceRecordCommandApi recordApi;
    private final CatalogQueryApi catalogApi;
    private final EmployeeQueryApi employeeApi;

    public TaskCompletionService(AppointmentTaskApi appointmentTaskApi, ServiceRecordCommandApi recordApi,
                                 CatalogQueryApi catalogApi, EmployeeQueryApi employeeApi) {
        this.appointmentTaskApi = appointmentTaskApi;
        this.recordApi = recordApi;
        this.catalogApi = catalogApi;
        this.employeeApi = employeeApi;
    }

    /**
     * @param serviceItems 洗护执行项（前端勾选框）；遛狗表没有这一列，传了也只是忽略
     */
    @Transactional
    public AppointmentTaskView finish(Long appointmentId, AuthPrincipal principal,
                                      List<String> serviceItems, String remark) {
        boolean storeWide = principal.permissions().contains("appointment:view:store");
        AppointmentTaskView v = appointmentTaskApi.finishService(appointmentId, principal.storeCtx(),
                principal.employeeId(), storeWide);
        if (!v.isChanged()) {
            // 幂等重放：状态早就是 COMPLETED，记录在那一次就写过了，这里绝不补第二条
            return v;
        }
        writeRecord(v, principal, serviceItems, remark);
        return v;
    }

    private void writeRecord(AppointmentTaskView v, AuthPrincipal principal,
                             List<String> serviceItems, String remark) {
        StoreServiceView svc = catalogApi.storeServiceOf(v.getStoreServiceId());
        String code = svc == null ? null : svc.getServiceCode();
        String role = executorRole(v.getStoreId(), v.getEmployeeId());
        // "店长顶班/代录"：点这一枪的人不是执行人本人
        boolean byManager = principal.employeeId() == null || !principal.employeeId().equals(v.getEmployeeId());
        // 判空在前：Set.of(...) 的 contains(null) 会直接 NPE（服务被删/目录行缺失时不该把"完成服务"打成 500）
        if (code != null && BATH_CODES.contains(code)) {
            recordApi.createBath(BathRecordDraft.builder()
                    .appointmentId(v.getAppointmentId()).petId(v.getPetId()).employeeId(v.getEmployeeId())
                    .storeServiceId(v.getStoreServiceId()).executorRole(role).byManager(byManager)
                    .startedAt(v.getActualStartAt()).finishedAt(v.getActualEndAt())
                    .serviceItems(serviceItems).remark(remark).build());
        } else if (CODE_WALK.equals(code)) {
            recordApi.createWalk(WalkRecordDraft.builder()
                    .appointmentId(v.getAppointmentId()).petId(v.getPetId()).employeeId(v.getEmployeeId())
                    .executorRole(role).byManager(byManager)
                    .startedAt(v.getActualStartAt()).finishedAt(v.getActualEndAt())
                    .durationMin(actualDurationMin(v)).remark(remark).build());
        } else {
            log.info("服务类型无对应履约表，仅推进预约状态 appointmentId={} serviceCode={}（医疗类本轮不建记录）",
                    v.getAppointmentId(), code);
        }
    }

    /** 实际时长（分钟）：起止都来自状态落定那一手，不足一分钟就是 0，不拿预约快照时长冒充做过的事。 */
    private int actualDurationMin(AppointmentTaskView v) {
        if (v.getActualStartAt() == null || v.getActualEndAt() == null) {
            return 0;
        }
        long minutes = Duration.between(v.getActualStartAt(), v.getActualEndAt()).toMinutes();
        return minutes < 0 ? 0 : (int) minutes;
    }

    /** 执行人当时角色：多角色取字典序首个（确定性优先于"挑一个像样的"），无角色写哨兵值。 */
    private String executorRole(Long storeId, Long employeeId) {
        if (storeId == null || employeeId == null) {
            return NO_ROLE;
        }
        List<EmployeeBrief> briefs = employeeApi.briefsInStore(List.of(employeeId), storeId);
        if (briefs == null || briefs.isEmpty() || briefs.get(0).getRoleCodes() == null
                || briefs.get(0).getRoleCodes().isEmpty()) {
            log.warn("执行人在本店无角色，履约记录 executor_role={} storeId={} employeeId={}",
                    NO_ROLE, storeId, employeeId);
            return NO_ROLE;
        }
        return briefs.get(0).getRoleCodes().stream().sorted().findFirst().orElse(NO_ROLE);
    }
}
