package com.petplatform.boarding.web;

import com.petplatform.boarding.api.BoardingQueryApi;
import com.petplatform.boarding.api.dto.BoardingStayDetailView;
import com.petplatform.boarding.api.dto.BoardingStayView;
import com.petplatform.boarding.api.dto.CareRecordRequest;
import com.petplatform.boarding.api.dto.CareRecordView;
import com.petplatform.boarding.api.dto.CaretakerAssignRequest;
import com.petplatform.boarding.api.dto.CheckinRequest;
import com.petplatform.boarding.service.BoardingStayService;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import jakarta.validation.Valid;
import lombok.Builder;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
/**
 * B 端托管入住单（P8 §4.3 /store/boarding-stays/**，ADR-013 列表 + ADR-015 闭环）。
 * 首页「在店宠物」数据源：status 缺省 = 在店集合 {CHECKED_IN, DAILY_CARE}。
 * 范围（ADR-015 §3）：店范围码（appointment:view:store=店长/前台）全见；
 * 护理员/医生等任务角色仅 caretaker=自己的单（不因持 care:view 得全店）。
 */
@RestController
@RequestMapping("/api/v1/store/boarding-stays")
public class StoreBoardingController {

    private final BoardingQueryApi boardingQueryApi;
    private final BoardingStayService boardingStayService;
    private final BoardingBoardAssembler boardAssembler;

    public StoreBoardingController(BoardingQueryApi boardingQueryApi, BoardingStayService boardingStayService,
                                   BoardingBoardAssembler boardAssembler) {
        this.boardingQueryApi = boardingQueryApi;
        this.boardingStayService = boardingStayService;
        this.boardAssembler = boardAssembler;
    }

    /**
     * 在住/待入住看板（缺省=在店集合）。行装配在 {@link BoardingBoardAssembler}（与历史分页端点共用）。
     *
     * <p>范围**下推进 SQL**：老写法是取回 50 条再 {@code canView} 过滤，店内匹配行数超限时
     * 护理员自己的单会被 LIMIT 截在外面静默消失（ADR-032 §问题3）。
     */
    @GetMapping
    @PreAuthorize("hasAuthority('perm:boarding:care:view')")
    public Result<List<BoardingBoardItem>> list(@RequestParam(required = false) String status) {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeId = requireStoreCtx(principal);
        Set<String> statuses = parseStatuses(status);
        Long scope = boardingStayService.readCaretakerScope(principal);
        if (BoardingStayService.SCOPE_NOTHING.equals(scope)) {
            return Result.ok(List.of());
        }
        List<BoardingStayView> stays = boardingQueryApi.stays(storeId, statuses, scope);
        return Result.ok(boardAssembler.toItems(storeId, stays, principal));
    }

    /**
     * 住单详情。路径变量约束成纯数字：不加 {@code \d+} 时 {@code /boarding-stays/page} 这类
     * **字面子路径**会被本路由吞掉——`id="page"` 转 Long 抛 NumberFormatException，
     * 最后由 GlobalExceptionHandler 兜成 10009「系统繁忙」，把"部署的镜像没有新端点"
     * 伪装成"服务端坏了"（2026-10-23 排查实录）。约束后未匹配路径走既有的
     * NoResourceFoundException → 10004「资源不存在」，与 {@code /zzz/yyy} 同口径。
     */
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('perm:boarding:care:view')")
    public Result<BoardingStayDetailView> detail(@PathVariable Long id,
                                                 @RequestParam(required = false) Long petId) {
        return Result.ok(boardingStayService.detail(id, petId, CurrentUserHolder.require()));
    }

    /**
     * 由本店预约补建「待入住」单（ADR-016 支付事件链 ensureExpected 的门店侧兜底入口）。
     * 复用 boarding:checkin 码：能办理入住的人才有资格生成待入住单，零权限迁移。
     * 请求体为空——宠物/日期/门店全部取自预约快照，前端不传任何业务字段（防越权改归属）。
     */
    @PostMapping("/from-appointment/{appointmentId}")
    @PreAuthorize("hasAuthority('perm:boarding:checkin')")
    public Result<BoardingStayDetailView> provision(@PathVariable Long appointmentId) {
        return Result.ok(boardingStayService.provisionFromAppointment(appointmentId, CurrentUserHolder.require()));
    }

    @PostMapping("/{id}/checkin")
    @PreAuthorize("hasAuthority('perm:boarding:checkin')")
    public Result<BoardingStayDetailView> checkin(@PathVariable Long id,
                                                  @Valid @RequestBody CheckinRequest request) {
        return Result.ok(boardingStayService.checkin(id, request, CurrentUserHolder.require()));
    }

    /**
     * 在住单补派/改派主责护理员（护理写闸只认 caretaker_id，入住时漏派就再没入口补）。
     * 复用 boarding:checkin：能办理入住、能在入住表单里指派护理员的人，才有资格改派——零权限迁移。
     * 数字约束同 {@link #detail}：非数字子路径不得走进 Long 转换。
     */
    @PostMapping("/{id:\\d+}/caretaker")
    @PreAuthorize("hasAuthority('perm:boarding:checkin')")
    public Result<BoardingStayDetailView> assignCaretaker(@PathVariable Long id,
                                                          @Valid @RequestBody CaretakerAssignRequest request) {
        return Result.ok(boardingStayService.assignCaretaker(id, request, CurrentUserHolder.require()));
    }

    @PostMapping("/{id}/checkout")
    @PreAuthorize("hasAuthority('perm:boarding:checkout')")
    public Result<BoardingStayDetailView> checkout(@PathVariable Long id) {
        return Result.ok(boardingStayService.checkout(id, CurrentUserHolder.require()));
    }

    /** 同 {@link #detail}：数字约束，避免非数字子路径被当成住单 ID 走进 Long 转换。 */
    @GetMapping("/{id:\\d+}/care-records")
    @PreAuthorize("hasAuthority('perm:boarding:care:view')")
    public Result<List<CareRecordView>> listCare(@PathVariable Long id,
                                                 @RequestParam(required = false) Long petId) {
        return Result.ok(boardingStayService.listCare(id, petId, CurrentUserHolder.require()));
    }

    @PostMapping("/{id}/care-records")
    @PreAuthorize("hasAuthority('perm:boarding:care:create')")
    public Result<CareRecordView> addCare(@PathVariable Long id,
                                          @Valid @RequestBody CareRecordRequest request) {
        return Result.ok(boardingStayService.addCare(id, request, CurrentUserHolder.require()));
    }

    private Set<String> parseStatuses(String status) {
        return BoardingStatuses.parse(status, BoardingQueryApi.ALL_STATUSES,
                BoardingQueryApi.IN_STORE_STATUSES, raw -> "非法托管状态: " + raw);
    }

    private Long requireStoreCtx(AuthPrincipal principal) {
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }

    /**
     * 客户列装配已收敛到 {@link BoardingCustomerAssembler}（ADR-028 口径，与 ADR-030 待支付看板共用）。
     */

    /** 在店看板条目（web 层组装：入住单 + 宠物/护理员/客户引用回显）。 */
    @Data
    @Builder
    public static class BoardingBoardItem {
        private Long id;
        private Long appointmentId;
        private String status;
        /** 关联预约状态（批量读模型回填）；在住单应为 SERVING，漂移时前端据此前置禁用离店 */
        private String appointmentStatus;
        private LocalDate checkinDate;
        private LocalDate checkoutDate;
        private LocalDateTime actualCheckinAt;
        private PetRef pet;
        private EmployeeRef caretaker;
        /** 客户（宠物主登记人）；任务角色为 null=整列不下发 */
        private BoardingCustomerAssembler.CustomerRef customer;
    }

    /** 客户引用见 {@link BoardingCustomerAssembler.CustomerRef}（两控制器共用，不留同形第二份）。 */
    @Data
    @Builder
    public static class PetRef {
        private Long id;
        private String name;
        private String species;
        private String breed;
        private String gender;
        /** 裸对象 key（ADR-029），不是可访问 URL */
        private String avatar;
        /** 头像 presigned GET；签不出为 null，前端回退占位 */
        private String avatarUrl;
    }

    @Data
    @Builder
    public static class EmployeeRef {
        private Long id;
        private String name;
    }
}
