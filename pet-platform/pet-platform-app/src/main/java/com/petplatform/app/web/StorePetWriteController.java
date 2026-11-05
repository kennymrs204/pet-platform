package com.petplatform.app.web;

import com.petplatform.app.service.StorePetDeleteService;
import com.petplatform.app.service.StorePetEditService;
import com.petplatform.app.service.StorePetHealthEditService;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.pet.api.dto.PetBaseUpdateRequest;
import com.petplatform.pet.api.dto.PetHealthUpdateRequest;
import com.petplatform.pet.api.dto.PetHealthVO;
import com.petplatform.pet.api.dto.PetVO;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端宠物资料写入：基础资料编辑沿用 customer:update；健康档案使用现有
 * pet:health:manage。门店、可见性和客户/宠物归属全部由服务端校验，
 * 请求体不包含 owner/storeId/操作人/updatedByRole。
 */
@RestController
@RequestMapping("/api/v1/store/pets")
public class StorePetWriteController {

    private final StorePetEditService editService;
    private final StorePetHealthEditService healthEditService;
    private final StorePetDeleteService deleteService;

    public StorePetWriteController(StorePetEditService editService,
                                   StorePetHealthEditService healthEditService,
                                   StorePetDeleteService deleteService) {
        this.editService = editService;
        this.healthEditService = healthEditService;
        this.deleteService = deleteService;
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('perm:customer:update')")
    public Result<PetVO> update(@PathVariable Long id, @Valid @RequestBody PetBaseUpdateRequest request) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(editService.update(requireStoreCtx(principal), principal, id, request));
    }

    @PutMapping("/{id}/health")
    @PreAuthorize("hasAuthority('perm:pet:view') and hasAuthority('perm:pet:health:manage')")
    public Result<PetHealthVO> updateHealth(@PathVariable Long id,
                                            @Valid @RequestBody PetHealthUpdateRequest request) {
        AuthPrincipal principal = CurrentUserHolder.require();
        return Result.ok(healthEditService.update(requireStoreCtx(principal), principal, id, request));
    }

    /**
     * B 端删除宠物。复用现有 B 端宠物写权限 customer:update（店长/前台/ADMIN）；
     * 预约/托管阻断、全局历史保留和并发锁在服务层完成。
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('perm:pet:view') and hasAuthority('perm:customer:update')")
    public Result<Void> delete(@PathVariable Long id) {
        AuthPrincipal principal = CurrentUserHolder.require();
        deleteService.delete(requireStoreCtx(principal), principal, id);
        return Result.ok();
    }

    private Long requireStoreCtx(AuthPrincipal principal) {
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }
}
