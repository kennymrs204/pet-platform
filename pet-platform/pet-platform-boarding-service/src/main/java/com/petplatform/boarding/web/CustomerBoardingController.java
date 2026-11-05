package com.petplatform.boarding.web;

import com.petplatform.boarding.api.dto.CustomerCareRecordView;
import com.petplatform.boarding.api.dto.CustomerStayView;
import com.petplatform.boarding.service.BoardingStayService;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.CurrentUserHolder;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * C 端托管日报（第八阶段未列，ADR-015 §4 补契约）。
 * 授权=宠物归属（UserPetApi.canManage，Service 层）；不存在/非归属统一 10004 不泄露存在性。
 * 视图已脱敏：无预约/笼位/员工 id、无门店内部字段。
 */
@RestController
@RequestMapping("/api/v1/customer/boarding-stays")
public class CustomerBoardingController {

    private final BoardingStayService boardingStayService;

    public CustomerBoardingController(BoardingStayService boardingStayService) {
        this.boardingStayService = boardingStayService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('perm:boarding:care:view')")
    public Result<com.petplatform.common.result.PageResult<CustomerStayView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(boardingStayService.customerList(
                CurrentUserHolder.require(), status, pageNum, pageSize));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('perm:boarding:care:view')")
    public Result<CustomerStayView> detail(@PathVariable Long id) {
        return Result.ok(boardingStayService.customerDetail(id, CurrentUserHolder.require()));
    }

    @GetMapping("/{id}/care-records")
    @PreAuthorize("hasAuthority('perm:boarding:care:view')")
    public Result<List<CustomerCareRecordView>> careRecords(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.ok(boardingStayService.customerCareRecords(id, CurrentUserHolder.require(), startDate, endDate));
    }
}
