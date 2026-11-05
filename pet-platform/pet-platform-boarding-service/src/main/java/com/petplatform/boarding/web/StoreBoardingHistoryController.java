package com.petplatform.boarding.web;

import com.petplatform.boarding.api.BoardingQueryApi;
import com.petplatform.boarding.api.dto.BoardingStayView;
import com.petplatform.boarding.service.BoardingStayService;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 历史住单分页（ADR-032 片2）：{@code GET /api/v1/store/boarding-stays/history}。
 *
 * <p>为什么另开一个端点而不是给 {@code /store/boarding-stays} 加分页参数：那会让**同一路径有两种响应外壳**
 * （裸数组 / PageResult），前端每个调用点都得先判断"这次按哪种解"，是最容易出错的一类契约。
 * 与 ADR-030「新增只读出口而非改既有端点语义」同法；既有列表端点及其消费方（首页在店卡、托管页主列表）零改动。
 *
 * <p>只服务会随时间累积的历史态（{@code CHECKED_OUT}/{@code ABNORMAL}）；在住/待入住仍走原端点——
 * 在店单量级 ≤ 笼位数，结构上不会触顶，给它铺分页是过度设计（ADR-022/024 判例）。
 * 状态集里**没有 CANCELLED**：取消=逻辑删（ADR-023），不在本表可查询集合内。
 *
 * <p>刻意不提供 keyword：按宠物名/客户名搜要跨模块"名字→id 集合"，会成为第二套真相
 * （ADR-032 §3 否掉候选 A3 的理由）；翻历史用 {@code dateFrom/dateTo}，打在 {@code checkout_date} 上，
 * 与排序同列（否则"按离店日排、按入住日筛"会让人看不懂自己看到的窗口）。
 */
@RestController
@RequestMapping("/api/v1/store/boarding-stays")
public class StoreBoardingHistoryController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final BoardingQueryApi boardingQueryApi;
    private final BoardingStayService boardingStayService;
    private final BoardingBoardAssembler boardAssembler;

    public StoreBoardingHistoryController(BoardingQueryApi boardingQueryApi, BoardingStayService boardingStayService,
                                          BoardingBoardAssembler boardAssembler) {
        this.boardingQueryApi = boardingQueryApi;
        this.boardingStayService = boardingStayService;
        this.boardAssembler = boardAssembler;
    }

    /**
     * @param status   逗号多值，缺省=全部历史态；含非历史态 → 10001（两个端点语义不许重叠）
     * @param dateFrom 离店日起（含）；@param dateTo 离店日止（含）；倒挂 10001
     * @param pageSize 缺省 20，钳制 1..100
     */
    @GetMapping("/history")
    @PreAuthorize("hasAuthority('perm:boarding:care:view')")
    public Result<PageResult<StoreBoardingController.BoardingBoardItem>> history(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false, defaultValue = "1") int pageNum,
            @RequestParam(required = false, defaultValue = "20") int pageSize) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        Set<String> statuses = parseHistoryStatuses(status);
        Long scope = boardingStayService.readCaretakerScope(principal);
        if (BoardingStayService.SCOPE_NOTHING.equals(scope)) {
            return Result.ok(PageResult.of(0L, Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1),
                    BoardingQueryApi.HISTORY_MAX_PAGE_SIZE), List.of()));
        }
        PageResult<BoardingStayView> page =
                boardingQueryApi.history(storeId, statuses, scope, dateFrom, dateTo, pageNum, pageSize);
        return Result.ok(PageResult.of(page.getTotal(), page.getPageNum(), page.getPageSize(),
                boardAssembler.toItems(storeId, page.getList(), principal)));
    }

    private Set<String> parseHistoryStatuses(String status) {
        return BoardingStatuses.parse(status, BoardingQueryApi.HISTORY_STATUSES,
                BoardingQueryApi.HISTORY_STATUSES,
                raw -> "历史端点只接受 " + BoardingQueryApi.HISTORY_STATUSES + " 组合，收到: " + raw);
    }

    private Long requireStoreCtx(AuthPrincipal principal) {
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }
}
