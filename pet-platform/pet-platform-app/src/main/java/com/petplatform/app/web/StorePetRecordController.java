package com.petplatform.app.web;

import com.petplatform.app.service.StorePetRecordQueryService;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端宠物服务历史（当前门店）。预约主表由 appointment 模块分页，
 * 履约/托管详情与员工姓名由 app 装配层批量补充；不新增表、不新增权限码。
 */
@RestController
@RequestMapping("/api/v1/store/pets")
public class StorePetRecordController {

    private final StorePetRecordQueryService recordQueryService;

    public StorePetRecordController(StorePetRecordQueryService recordQueryService) {
        this.recordQueryService = recordQueryService;
    }

    @GetMapping("/{id}/service-records")
    @PreAuthorize("hasAuthority('perm:pet:view') and hasAuthority('perm:record:view:pet')")
    public Result<PageResult<StorePetRecordQueryService.PetServiceRecordRow>> list(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(recordQueryService.page(requireStoreCtx(principal), principal, id, pageNum, pageSize));
    }

    private Long requireStoreCtx(AuthPrincipal principal) {
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }
}
