package com.petplatform.boarding.api;

import com.petplatform.boarding.api.dto.BoardingStayView;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 托管只读查询（表 Owner：boarding 模块）。首页「在店宠物」数据源（ADR-013）。
 * 在店口径 = status ∈ {CHECKED_IN, DAILY_CARE}（生命周期 入住→每日护理→离店，第三阶段词典 §BoardingStay）。
 */
public interface BoardingQueryApi {

    Set<String> IN_STORE_STATUSES = Set.of("CHECKED_IN", "DAILY_CARE");
    Set<String> ALL_STATUSES = Set.of("EXPECTED", "CHECKED_IN", "DAILY_CARE", "CHECKED_OUT", "ABNORMAL");
    /**
     * 历史态（会随时间累积、因此需要分页的那部分）。注意状态集里**没有 CANCELLED**：
     * 取消=逻辑删（deleted=id，ADR-023），根本不出现在本表的可查询集合里。
     */
    Set<String> HISTORY_STATUSES = Set.of("CHECKED_OUT", "ABNORMAL");
    int LIST_LIMIT = 50;
    int HISTORY_MAX_PAGE_SIZE = 100;
    /** 主列表分页（V3）：缺省 20、上限 100，与历史分页同族口径 */
    int PAGE_DEFAULT_SIZE = 20;
    int PAGE_MAX_SIZE = 100;

    /** 在店宠物数 = COUNT(status ∈ IN_STORE_STATUSES)。 */
    long countInStore(Long storeId);

    /** 按状态集合列在住单（确定性排序：checkin_date → id），上限 LIST_LIMIT 条；全店范围。 */
    List<BoardingStayView> stays(Long storeId, Collection<String> statuses);

    /**
     * 同上，但把"任务角色只看自己被指派的单"这条 ADR-015 §3 口径**下推进 WHERE**。
     *
     * <p>为什么不沿用"取回再 filter"：老实现是 {@code LIMIT 50} 之后再 filter，店内在住单一过 50，
     * 护理员自己的单会被 LIMIT 截在外面**静默消失**（不报错也不提示）。传 null=全店，
     * 由调用方按 {@code appointment:view:store} 判定；无员工身份者应直接给空集而不是传 0。
     */
    List<BoardingStayView> stays(Long storeId, Collection<String> statuses, Long caretakerId);

    /**
     * 历史住单分页（ADR-032 片2）：只服务"已离店/异常"这类会累积的状态，
     * 排序 {@code checkout_date DESC → id DESC}，日期区间打在 checkout_date（与排序同列）。
     * 入参钳制（pageSize≤100、pageNum≥1、status 只收历史态、dateFrom≤dateTo）收在本方法，控制器不重复实现。
     *
     * @param caretakerId null=全店范围；非 null=仅该护理员主责的单（同 {@link #stays(Long,Collection,Long)} 的理由）
     */
    com.petplatform.common.result.PageResult<BoardingStayView> history(Long storeId, Collection<String> statuses,
                                                                      Long caretakerId, java.time.LocalDate dateFrom,
                                                                      java.time.LocalDate dateTo, int pageNum,
                                                                      int pageSize);

    /**
     * 主列表服务端分页 + 服务端关键词过滤（V3）。
     *
     * <p>与 {@link #stays(Long, Collection, Long)} 的区别不是"加了个参数"，而是**过滤发生在 LIMIT 之前**：
     * 老列表端点结构上最多只能看到前 50 条，搜索/计数都只能在那 50 条里打转。
     * 钳制（pageNum≥1、pageSize∈1..100）收在这里，返回体里的 pageNum/pageSize 即实际生效值。
     *
     * <p>total 与列表取自**同一个 query 实例**（同一段 WHERE），不给它们各拼一份参数的机会。
     */
    com.petplatform.common.result.PageResult<BoardingStayView> page(
            com.petplatform.boarding.api.dto.BoardingStayPageQuery query);

    /**
     * 页签角标用的状态计数：一次 GROUP BY 出全部状态，再按页签语义合并。
     * 过滤条件与 {@link #page} 完全同源（含关键词），所以"关键词命中多少条"与列表看到的是同一件事。
     *
     * @return status → 条数；未出现的状态表示 0
     */
    java.util.Map<String, Long> countByStatus(com.petplatform.boarding.api.dto.BoardingStayPageQuery query);

    /** 本店在店（status ∈ IN_STORE_STATUSES）宠物 ID 集（ADR-014 B 端店范围并集项）。 */
    Set<Long> inStorePetIds(Long storeId);

    /** 我作为主责护理员、本店在店的宠物 ID 集（ADR-014 任务范围并集项）。 */
    Set<Long> caretakerPetIds(Long storeId, Long caretakerId);

    /** 按宠物集查在店住单（status ∈ IN_STORE_STATUSES；checkin_date→id 排序，上限 100；无在店记录的宠物静默跳过）。 */
    List<BoardingStayView> currentStays(Collection<Long> petIds);

    /**
     * 删除保护：该宠物是否存在仍需继续处理的托管住单（全局，不按门店过滤）。
     * 阻断口径 = deleted=0 且 status 不为 CHECKED_OUT；EXPECTED/CHECKED_IN/DAILY_CARE/ABNORMAL
     * 及未知状态均阻断。取消=逻辑删（deleted=id），天然排除。
     */
    boolean hasBlockingByPet(Long petId);

    /**
     * 按预约 id 批量取托管住单（含 EXPECTED/CHECKED_IN/DAILY_CARE/CHECKED_OUT/ABNORMAL，不含逻辑删除）。
     * 只用于已由 appointment 模块按门店/宠物/任务范围过滤后的当前页预约；仍以 storeId 再兜底一次隔离。
     * 无住单的预约不出现在结果里，调用方保留预约主记录。
     */
    List<BoardingStayView> staysByAppointments(Long storeId, Collection<Long> appointmentIds);
}
