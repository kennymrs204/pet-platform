package com.petplatform.boarding.web;

import com.petplatform.boarding.api.BoardingQueryApi;
import com.petplatform.boarding.api.dto.BoardingStayPageQuery;
import com.petplatform.boarding.api.dto.BoardingStayView;
import com.petplatform.boarding.service.BoardingStayService;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import lombok.Builder;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 托管主列表的服务端查询出口（V3）：
 * {@code GET /api/v1/store/boarding-stays/page} 与 {@code GET /api/v1/store/boarding-stays/status-counts}。
 *
 * <p><b>为什么不直接给 {@code GET /store/boarding-stays} 加分页参数</b>：那条路径现在返回**裸数组**，
 * 真实调用方除托管页外还有首页「在店宠物」卡（{@code dashboardApi.inStorePets}）、宠物照顾工作台
 * （{@code CarePage}）与若干验收脚本。同一 URL 出现两种响应外壳（数组 / PageResult）是最容易出错的一类契约，
 * 每个调用点都得先判断"这次按哪种解"。与 ADR-030/032「新增只读出口而非改既有端点语义」同法：
 * 既有端点及其消费方零改动。
 *
 * <p>权限与范围沿用读侧既有口径：入口码 {@code boarding:care:view}，
 * 店范围/护理员范围由 {@link BoardingStayService#readCaretakerScope} 下推 WHERE，
 * 客户列与手机号脱敏仍由 {@link BoardingBoardAssembler} 按 {@code customer:view:store} 决定。
 * 关键词只能在这套条件**之内**缩小结果，不能绕过它们。
 */
@RestController
@RequestMapping("/api/v1/store/boarding-stays")
public class StoreBoardingSearchController {

    /** 页签角标的统计范围 = 主列表可出现的全部状态（与前端页签定义同源）。 */
    private static final Set<String> TAB_STATUSES = BoardingQueryApi.ALL_STATUSES;

    private final BoardingQueryApi boardingQueryApi;
    private final BoardingStayService boardingStayService;
    private final BoardingBoardAssembler boardAssembler;
    private final BoardingKeywordResolver keywordResolver;

    public StoreBoardingSearchController(BoardingQueryApi boardingQueryApi, BoardingStayService boardingStayService,
                                         BoardingBoardAssembler boardAssembler,
                                         BoardingKeywordResolver keywordResolver) {
        this.boardingQueryApi = boardingQueryApi;
        this.boardingStayService = boardingStayService;
        this.boardAssembler = boardAssembler;
        this.keywordResolver = keywordResolver;
    }

    /**
     * @param status   逗号多值，缺省=在店集合（与既有列表端点同语义）
     * @param keyword  宠物名/客户昵称/手机号/住单 id/预约 id；超 50 字 10001
     * @param pageSize 缺省 20，钳制 1..100（钳制在 {@code BoardingQueryService#page}）
     */
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('perm:boarding:care:view')")
    public Result<PageResult<StoreBoardingController.BoardingBoardItem>> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "1") int pageNum,
            @RequestParam(required = false, defaultValue = "20") int pageSize) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), BoardingQueryApi.PAGE_MAX_SIZE);
        BoardingStayPageQuery query = buildQuery(status, BoardingQueryApi.IN_STORE_STATUSES, keyword,
                page, size, principal, storeId);
        if (query == null) {
            return Result.ok(PageResult.of(0L, page, size, List.of()));
        }
        PageResult<BoardingStayView> result = boardingQueryApi.page(query);
        return Result.ok(PageResult.of(result.getTotal(), result.getPageNum(), result.getPageSize(),
                boardAssembler.toItems(storeId, result.getList(), principal)));
    }

    /**
     * 页签角标：一次 GROUP BY 出五个页签的条数，不逐页签拉全量。
     * 关键词与列表同源同条件——输入关键词后各页签按该关键词实时匹配，切换页签不改变角标语义。
     */
    @GetMapping("/status-counts")
    @PreAuthorize("hasAuthority('perm:boarding:care:view')")
    public Result<BoardingStatusCounts> statusCounts(@RequestParam(required = false) String keyword) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        BoardingStayPageQuery query = buildQuery(null, TAB_STATUSES, keyword, 1, BoardingQueryApi.PAGE_DEFAULT_SIZE,
                principal, storeId);
        if (query == null) {
            return Result.ok(BoardingStatusCounts.builder().build());
        }
        Map<String, Long> byStatus = boardingQueryApi.countByStatus(query);
        long inStore = sum(byStatus, BoardingQueryApi.IN_STORE_STATUSES);
        return Result.ok(BoardingStatusCounts.builder()
                .all(sum(byStatus, TAB_STATUSES))
                .inStore(inStore)
                .expected(count(byStatus, "EXPECTED"))
                .checkedOut(count(byStatus, "CHECKED_OUT"))
                .abnormal(count(byStatus, "ABNORMAL"))
                .build());
    }

    /**
     * 组装查询；返回 null = 当前账号在本店没有任何可见范围（无员工身份的任务角色），调用方直接给空结果，
     * 不打库也不让 {@code caretaker_id = -1} 参与匹配。
     *
     * @param fallbackStatuses 参数缺省时的状态集合——列表端点是「在店集合」，
     *                         角标端点必须是「五种全量」，否则 EXPECTED/CHECKED_OUT/ABNORMAL 会被静默算成 0
     */
    private BoardingStayPageQuery buildQuery(String status, Set<String> fallbackStatuses, String keyword,
                                             int pageNum, int pageSize,
                                             AuthPrincipal principal, Long storeId) {
        Set<String> statuses = BoardingStatuses.parse(status, BoardingQueryApi.ALL_STATUSES,
                fallbackStatuses, raw -> "非法托管状态: " + raw);
        Long scope = boardingStayService.readCaretakerScope(principal);
        if (BoardingStayService.SCOPE_NOTHING.equals(scope)) {
            return null;
        }
        BoardingKeywordResolver.Resolved resolved = keywordResolver.resolve(keyword);
        return BoardingStayPageQuery.builder()
                .storeId(storeId)
                .statuses(statuses)
                .caretakerId(scope)
                .keyword(resolved.isKeyword())
                .keywordNumeric(resolved.getNumeric())
                .keywordPetIds(resolved.getPetIds())
                .pageNum(pageNum)
                .pageSize(pageSize)
                .build();
    }

    private static long sum(Map<String, Long> byStatus, Set<String> statuses) {
        return statuses.stream().mapToLong(s -> byStatus.getOrDefault(s, 0L)).sum();
    }

    private static long count(Map<String, Long> byStatus, String status) {
        return byStatus.getOrDefault(status, 0L);
    }

    private Long requireStoreCtx(AuthPrincipal principal) {
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }

    /**
     * 页签计数。字段是 long → Jackson 一律字符串化（含基本类型 long），前端取值时要显式 Number()。
     * 「在店中」= CHECKED_IN + DAILY_CARE，合并规则留在服务端，不在前端重述一遍状态语义。
     */
    @Data
    @Builder
    public static class BoardingStatusCounts {
        private long all;
        private long inStore;
        private long expected;
        private long checkedOut;
        private long abnormal;
    }
}
