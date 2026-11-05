package com.petplatform.schedule.api;

import com.petplatform.schedule.api.dto.KennelCapacityView;
import com.petplatform.schedule.api.dto.KennelResourceView;
import com.petplatform.schedule.api.dto.OccupancyView;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 笼位占用与容量（表 Owner：schedule；sch_resource / sch_resource_schedule，ADR-015）。
 * boarding 入住/离店的占用确认、迁移、释放全部经本 API 收口，禁止跨模块直查 sch_* 表。
 * 占用行防超卖最后防线 = UNIQUE(resource_id, work_date, slot_no)，slot_no=99 天级（ADR-005-2）。
 */
public interface ResourceOccupancyApi {

    /** 资源详情；不存在或不属该店返回 null（跨店按不存在处理，防探测）。 */
    KennelResourceView resource(Long storeId, Long resourceId);

    /**
     * 锁定本店资源行并返回锁定后的最新状态（SELECT ... FOR UPDATE）。
     * 锁随调用方事务提交/回滚释放；调用方必须已在业务事务中，且应把本调用放在新占用写入之前。
     * 本层不强制 AVAILABLE：新分配由调用方拒绝非 AVAILABLE，入住沿用既有预占按兼容口径放行。
     * 资源不存在、已软删或不属于该店返回 null。
     */
    KennelResourceView lockResourceForUse(Long storeId, Long resourceId);

    /** 笼位容量（按日）；type 空 = 全部笼位类型逐类返回。 */
    List<KennelCapacityView> kennelCapacity(Long storeId, LocalDate date, String type);

    /** 该预约当前全部 DAY 占用行（入住时识别预占笼位）。 */
    List<OccupancyView> occupancyOfAppointment(Long appointmentId);

    /** 该预约某日 TIME_SLOT 占用位图（resourceId → slotNo 集合），用于改期排除自身旧占用。 */
    Map<Long, Set<Integer>> timeSlotOccupancyOfAppointment(Long appointmentId, LocalDate date);

    /**
     * 目标资源在指定日期/时段是否被“其他预约”占用（锁定读；排除 excludeAppointmentId 自身）。
     * 必须在已锁定目标资源行的事务内调用。
     */
    boolean hasOtherTimeSlotOccupancyForUpdate(Long resourceId, LocalDate date, int fromSlot,
                                               int toSlotExclusive, Long excludeAppointmentId);

    /**
     * 目标资源在 [from, toExclusive) 内是否存在“其他预约”的 DAY 占用（锁定读；排除 excludeAppointmentId 自身）。
     * 必须在已锁定目标资源行的事务内调用。
     */
    boolean hasOtherDayOccupancyForUpdate(Long resourceId, LocalDate from, LocalDate toExclusive,
                                          Long excludeAppointmentId);

    /**
     * 入住占用确认/迁移（同事务调用）：区间 [from, toExclusive) 逐日 DAY 行。
     * - 该预约在目标笼位已有整段占用 → 幂等返回；
     * - 有其他笼位占用 → 删旧插新（迁移，ADR-005-7）；
     * - 撞唯一键（他人已占）→ 72001。
     */
    void confirmOrMoveKennel(Long resourceId, Long appointmentId, LocalDate from, LocalDate toExclusive);

    /** 释放该预约 [from, toExclusive) 的 DAY 占用（物理删行，P2 语义）；返回删除行数。 */
    int releaseOccupancy(Long appointmentId, LocalDate from, LocalDate toExclusive);

    /** 释放该预约全部占用行（TIME_SLOT+DAY；超时/取消统一入口，ADR-016 §4）。 */
    int releaseAll(Long appointmentId);

    /** 本店该类型 AVAILABLE 资源列表（id 升序；可约候选槽资源集，ADR-016 §7）。 */
    List<KennelResourceView> availableResources(Long storeId, String type);

    /** 该店该类型当日 TIME_SLOT 占用位图：resourceId → 已占 slotNo 集合（可约/创建同一数据源）。 */
    Map<Long, Set<Integer>> daySlotOccupancy(Long storeId, String type, LocalDate date);

    /**
     * 锁定一组时段（创建预约事务内调用，REQUIRED 加入调用方事务）：
     * 逐槽插行，撞 UNIQUE(resource_id, work_date, slot_no) → 50001（调用方事务整体回滚）。
     */
    void occupyTimeSlots(Long resourceId, Long appointmentId, LocalDate date, List<Integer> slotNos);

    /** [from, toExclusive) 区间内存在 DAY 占用的资源 id 集（MULTI_DAY 空闲笼位筛选，ADR-016 §6）。 */
    Set<Long> kennelIdsOccupiedBetween(Long storeId, LocalDate from, LocalDate toExclusive);
}
