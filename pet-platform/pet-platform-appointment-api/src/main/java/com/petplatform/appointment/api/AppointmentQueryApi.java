package com.petplatform.appointment.api;

import com.petplatform.appointment.api.dto.AppointmentStoreQuery;
import com.petplatform.appointment.api.dto.AppointmentStoreView;
import com.petplatform.appointment.api.dto.AppointmentSelfView;
import com.petplatform.appointment.api.dto.BoardingAppointmentView;
import com.petplatform.appointment.api.dto.CustomerAggView;
import com.petplatform.appointment.api.dto.CustomerVisitView;
import com.petplatform.appointment.api.dto.LastAppointmentView;
import com.petplatform.appointment.api.dto.PetServiceVisitView;
import com.petplatform.appointment.api.dto.TimeoutAppointmentView;
import com.petplatform.common.result.PageResult;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 预约只读查询（表 Owner：appointment 模块）。B 端首页「今日待办」数据源（ADR-013）。
 * ADR-005-13：B 端看板直查 apt_appointment，无缓存。
 */
public interface AppointmentQueryApi {

    /** 门店×服务日期分页查询；确定性排序：start_time（NULL 最后）→ id。 */
    PageResult<AppointmentStoreView> storeAppointments(AppointmentStoreQuery query);

    /** 门店历史业务关联的宠物 ID 集（distinct；ADR-014 B 端店范围）。 */
    Set<Long> petIdsInStore(Long storeId);

    /** 员工在本店被分配（含后补）的宠物 ID 集（distinct；ADR-014 任务范围）。employeeId 为空返回空集。 */
    Set<Long> assignedPetIds(Long storeId, Long employeeId);

    /**
     * 某员工某日在本店**占着档期**的预约条数（ADR-034 §3：改/删排班前的预检）。
     *
     * <p>非终态口径 = {PENDING_PAYMENT, CONFIRMED, WAIT_SERVICE, SERVING}。含待支付是有意的：
     * 它同样占着员工那天的时段，且超时扫描会自动取消，不会永久卡住排班。
     * 只数 {@code employee_id} 已定的预约——后补分配（employee_id IS NULL）没有指定到人，不构成档期占用。
     */
    long countScheduleOccupying(Long storeId, Long employeeId, java.time.LocalDate workDate);

    /** 按宠物取本店最近一条预约快照（service_date desc→id desc）；无预约的宠物静默跳过。 */
    List<LastAppointmentView> lastAppointments(Long storeId, Collection<Long> petIds);

    /**
     * 当前门店内指定宠物的全部预约 ID（distinct）。
     * 供最近履约摘要按“本店预约”二次过滤；履约表没有 store_id，不能跨店取全局最新履约。
     * 空宠物集/空门店短路，不打库。
     */
    Set<Long> appointmentIdsInStore(Long storeId, Collection<Long> petIds);

    /**
     * 按门店 + 宠物分页查询服务预约历史（service_date desc→id desc）。
     * <p>employeeId 非空时把任务角色范围下推进 SQL（只看该执行人的预约）；null 表示店宽范围。
     * 调用方必须先完成宠物可见性校验；本方法不再做授权判断。
     * 返回预约主数据，履约/托管详情由 app 层按 appointmentId 批量补充。
     */
    PageResult<PetServiceVisitView> petServiceHistory(Long storeId, Long petId, Long employeeId,
                                                       int pageNum, int pageSize);

    /**
     * 该宠物是否存在需要继续处理的预约（全局，不按门店过滤）。
     * 阻断口径 = deleted=0 且状态不属于 {COMPLETED, CANCELLED}；PENDING_PAYMENT /
     * CONFIRMED / CANCEL_REQUESTED / WAIT_SERVICE / SERVING 以及未知状态均按未完成处理。
     * 供宠物删除保护使用；调用方负责当前门店可见性/删除权限。
     */
    boolean hasBlockingByPet(Long petId);

    /** 托管视角预约快照（ADR-015）；不存在/已删返回 null。 */
    BoardingAppointmentView boardingSnapshot(Long appointmentId);

    /**
     * 批量取预约状态（B 端住单看板行回填 {@code appointmentStatus} 用，纯读模型）。
     * 用途：在住单（stay CHECKED_IN/DAILY_CARE）与预约（应 SERVING）的一致性前置提示——
     * 夹具或异常数据漂移时，前端能在离店确认面板先禁用并说明，而不是让用户点了才吃 50003。
     * 不存在/已删的 id 不出现在返回 Map；入参为空返回空 Map。
     */
    Map<Long, String> boardingStatuses(Collection<Long> appointmentIds);

    /**
     * 用户名下托管类（MULTI_DAY）预约 id 集（ADR-021）：
     * boarding 模块据此推导"我的住单"归属（stay 表无 user_id，跨 Owner 边只走本方法）。
     */
    List<Long> boardingAppointmentIdsOfUser(Long userId);

    /**
     * 预约 id → 下单顾客 user_id（不存在返回 null）。住单无 user_id 列，
     * 护理记录通知链（BoardingCareRecordedEvent）据此解析宠物主人收件人。
     */
    Long ownerIdOfAppointment(Long appointmentId);

    /**
     * 入住办理迁移 CONFIRMED→SERVING（§7.1 MULTI_DAY 专用，跳过 WAIT_SERVICE）。
     * 条件更新（WHERE status=CONFIRMED），0 行影响 → 50003。仅 boarding 模块同事务调用。
     */
    void startBoarding(Long appointmentId);

    /**
     * 离店办理迁移 SERVING→COMPLETED。条件更新，0 行 → 50003。仅 boarding 模块同事务调用。
     */
    void completeBoarding(Long appointmentId);

    /** C 端"我的预约"分页（status 空=全部；created_at desc→id desc；ADR-016）。 */
    PageResult<AppointmentSelfView> selfPage(Long userId, String status, int pageNum, int pageSize);

    /** C 端详情；非本人/不存在返回 null（调用方按 10004 处理，不泄露存在性）。 */
    AppointmentSelfView selfDetail(Long userId, Long appointmentId);

    /** 超时扫描：PENDING_PAYMENT ∧ expire_at < now（idx_status_expire），limit 上限保护。 */
    List<TimeoutAppointmentView> scanTimeoutAppointments(java.time.LocalDateTime now, int limit);

    /**
     * 本店客户聚合（ADR-017 §1/§6，B 端客户视图数据源）：
     * 按 user_id 分组取首末到店日与次数；员工账号由调用方剔除（预约域不感知员工域）。
     */
    List<CustomerAggView> customerAggInStore(Long storeId);

    /** 某客户在本店的到店史（service_date desc→id desc，limit 钳制 1..100、offset 钳制 ≥0；ADR-017 §5）。 */
    List<CustomerVisitView> visitsOfCustomer(Long storeId, Long userId, int limit, int offset);
}
