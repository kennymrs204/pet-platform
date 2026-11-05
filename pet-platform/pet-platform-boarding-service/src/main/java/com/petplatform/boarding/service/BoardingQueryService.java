package com.petplatform.boarding.service;

import com.petplatform.boarding.api.BoardingQueryApi;
import com.petplatform.boarding.api.dto.BoardingStayPageQuery;
import com.petplatform.boarding.api.dto.BoardingStayView;
import com.petplatform.boarding.mapper.BoardingQueryMapper;
import com.petplatform.common.result.PageResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class BoardingQueryService implements BoardingQueryApi {

    /**
     * 宠物 id 分片大小。它约束的是**单条 SQL 的 IN 列表长度**（防止语句无限膨胀），
     * 不是结果集大小——候选超过一片时片数随候选数增长，一条都不少（见 {@link #groupsOf}）。
     */
    static final int PET_ID_CHUNK = 500;

    /** 全局顺序 = 列表 SQL 的 {@code ORDER BY checkin_date ASC, id ASC}，归并必须按同一口径。 */
    private static final java.util.Comparator<BoardingQueryMapper.StayRow> ROW_ORDER =
            java.util.Comparator.comparing(BoardingQueryMapper.StayRow::getCheckinDate,
                            java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                    .thenComparing(BoardingQueryMapper.StayRow::getId,
                            java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()));

    private final BoardingQueryMapper mapper;

    public BoardingQueryService(BoardingQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long countInStore(Long storeId) {
        return mapper.countByStatuses(storeId, IN_STORE_STATUSES);
    }

    @Override
    public List<BoardingStayView> stays(Long storeId, Collection<String> statuses) {
        return stays(storeId, statuses, null);
    }

    @Override
    public List<BoardingStayView> stays(Long storeId, Collection<String> statuses, Long caretakerId) {
        return mapper.selectStays(storeId, statuses, caretakerId, LIST_LIMIT).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 历史住单分页。钳制收在这里（控制器不重复实现）：pageSize 落 1..100、pageNum≥1、
     * status 只收历史态、日期倒挂 10001——越界值静默改写会让 total 与页码对不上，故非法即拒。
     */
    @Override
    public com.petplatform.common.result.PageResult<BoardingStayView> history(
            Long storeId, Collection<String> statuses, Long caretakerId,
            java.time.LocalDate dateFrom, java.time.LocalDate dateTo, int pageNum, int pageSize) {
        if (statuses == null || statuses.isEmpty() || !HISTORY_STATUSES.containsAll(statuses)) {
            throw new com.petplatform.common.error.BusinessException(
                    com.petplatform.common.error.CommonErrorCode.PARAM_INVALID, "历史端点只接受已离店/异常状态");
        }
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new com.petplatform.common.error.BusinessException(
                    com.petplatform.common.error.CommonErrorCode.PARAM_INVALID, "日期区间倒挂");
        }
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), HISTORY_MAX_PAGE_SIZE);
        long total = mapper.countHistory(storeId, statuses, caretakerId, dateFrom, dateTo);
        List<BoardingStayView> list = total == 0 ? List.of()
                : mapper.selectHistoryPage(storeId, statuses, caretakerId, dateFrom, dateTo, (page - 1) * size, size)
                .stream().map(this::toView).toList();
        return com.petplatform.common.result.PageResult.of(total, page, size, list);
    }

    /**
     * 主列表分页。钳制收在这里（与 {@link #history} 同法）：pageNum&lt;1 归 1、pageSize 落 1..100，
     * 越界值静默改写会让 total 与页码对不上；total=0 时不再打第二次查询。
     *
     * <p>候选没超一片时就是**一条 SQL**（形状与 V3 完全一致）。超一片时按互斥分片分别查
     * count 与"该片前 pageNum*pageSize 行"，再按同一全局顺序归并切片：
     * 分片互斥 ⇒ Σcount = 完整匹配数、归并后取页 = 完整有序结果，既不重复也不漏。
     */
    @Override
    public PageResult<BoardingStayView> page(BoardingStayPageQuery query) {
        int page = Math.max(query.getPageNum(), 1);
        int size = Math.min(Math.max(query.getPageSize(), 1), PAGE_MAX_SIZE);
        List<BoardingStayPageQuery> groups = groupsOf(query);
        if (groups.size() == 1) {
            long total = mapper.countStays(query);
            List<BoardingStayView> list = total == 0 ? List.of()
                    : mapper.selectStayPage(query, (page - 1) * size, size).stream().map(this::toView).toList();
            return PageResult.of(total, page, size, list);
        }
        long total = 0;
        List<BoardingQueryMapper.StayRow> head = new ArrayList<>();
        int need = page * size; // 每组只需前 need 行，就足以拼出全局前 need 行
        for (BoardingStayPageQuery group : groups) {
            total += mapper.countStays(group);
            if (need > 0) {
                head.addAll(mapper.selectStayPage(group, 0, need));
            }
        }
        java.util.Map<Long, BoardingQueryMapper.StayRow> unique = new java.util.LinkedHashMap<>();
        head.stream().sorted(ROW_ORDER).forEach(r -> unique.putIfAbsent(r.getId(), r));
        List<BoardingStayView> list = unique.values().stream()
                .skip((long) (page - 1) * size)
                .limit(size)
                .map(this::toView)
                .toList();
        return PageResult.of(total, page, size, list);
    }

    /** 页签角标：一次 GROUP BY 一片，不按页签逐个拉全量；分片互斥 ⇒ 同一状态跨片相加不重复计数。 */
    @Override
    public Map<String, Long> countByStatus(BoardingStayPageQuery query) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (BoardingStayPageQuery group : groupsOf(query)) {
            for (BoardingQueryMapper.StatusCount row : mapper.countStaysByStatus(group)) {
                byStatus.merge(row.getStatus(), row.getTotal(), Long::sum);
            }
        }
        return byStatus;
    }

    /**
     * 把一次逻辑查询切成互斥的物理分片。
     *
     * <p>切法：数值命中（住单 id / 预约 id）单独一片，宠物 id 按 {@link #PET_ID_CHUNK} 分片，
     * 宠物片显式 {@code NOT (id=… OR appointment_id=…)} 排除数值片已取走的行。
     * 一条住单只有一个 pet_id、一个 id、一个 appointment_id ⇒ 恰好落在一个片里。
     */
    List<BoardingStayPageQuery> groupsOf(BoardingStayPageQuery q) {
        if (!q.isKeyword() || q.isKeywordImpossible()) {
            return List.of(q);
        }
        List<Long> petIds = q.getKeywordPetIds() == null ? List.of() : List.copyOf(q.getKeywordPetIds());
        Long numeric = q.getKeywordNumeric();
        if (petIds.size() <= PET_ID_CHUNK) {
            return List.of(q);
        }
        List<BoardingStayPageQuery> groups = new ArrayList<>();
        if (numeric != null) {
            groups.add(group(q, numeric, List.of(), BoardingStayPageQuery.CLAUSE_NUMERIC_ONLY));
        }
        for (int from = 0; from < petIds.size(); from += PET_ID_CHUNK) {
            List<Long> chunk = petIds.subList(from, Math.min(from + PET_ID_CHUNK, petIds.size()));
            groups.add(group(q, numeric, chunk, numeric != null
                    ? BoardingStayPageQuery.CLAUSE_PET_EXCLUDING_NUMERIC
                    : BoardingStayPageQuery.CLAUSE_PET_ONLY));
        }
        return groups;
    }

    private static BoardingStayPageQuery group(BoardingStayPageQuery q, Long numeric,
                                               List<Long> petIds, int clause) {
        return BoardingStayPageQuery.builder()
                .storeId(q.getStoreId())
                .statuses(q.getStatuses())
                .caretakerId(q.getCaretakerId())
                .keyword(true)
                .keywordNumeric(numeric)
                .keywordPetIds(petIds)
                .keywordClause(clause)
                .pageNum(q.getPageNum())
                .pageSize(q.getPageSize())
                .build();
    }

    @Override
    public Set<Long> inStorePetIds(Long storeId) {
        return storeId == null ? Set.of() : mapper.selectInStorePetIds(storeId, IN_STORE_STATUSES);
    }

    @Override
    public Set<Long> caretakerPetIds(Long storeId, Long caretakerId) {
        if (storeId == null || caretakerId == null || caretakerId == 0L) {
            return Set.of();
        }
        return mapper.selectCaretakerPetIds(storeId, caretakerId, IN_STORE_STATUSES);
    }

    @Override
    public List<BoardingStayView> currentStays(Collection<Long> petIds) {
        if (petIds == null || petIds.isEmpty()) {
            return List.of();
        }
        return mapper.selectCurrentStays(petIds, IN_STORE_STATUSES).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    public boolean hasBlockingByPet(Long petId) {
        return petId != null && mapper.existsBlockingByPet(petId) > 0;
    }

    @Override
    public List<BoardingStayView> staysByAppointments(Long storeId, Collection<Long> appointmentIds) {
        if (storeId == null || storeId == 0L || appointmentIds == null || appointmentIds.isEmpty()) {
            return List.of();
        }
        return mapper.selectStaysByAppointments(storeId, appointmentIds).stream()
                .map(this::toView)
                .toList();
    }

    private BoardingStayView toView(BoardingQueryMapper.StayRow r) {
        return BoardingStayView.builder()
                .id(r.getId())
                .appointmentId(r.getAppointmentId())
                .petId(r.getPetId())
                .checkinDate(r.getCheckinDate())
                .checkoutDate(r.getCheckoutDate())
                .actualCheckinAt(r.getActualCheckinAt())
                .status(r.getStatus())
                .caretakerId(r.getCaretakerId())
                .build();
    }
}
