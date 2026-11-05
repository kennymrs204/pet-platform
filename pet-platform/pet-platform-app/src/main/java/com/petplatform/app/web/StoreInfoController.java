package com.petplatform.app.web;

import com.petplatform.app.service.PlatformParamQuery;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.store.api.StoreInfoApi;
import com.petplatform.store.api.dto.StoreInfoUpdate;
import com.petplatform.store.api.dto.StoreInfoView;
import jakarta.validation.Valid;
import lombok.Builder;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端门店设置（P8 §4.3 :236 GET/PUT /store/info，权限码 store:info:update：ADMIN/店长，前台无此码）。
 *
 * 落 app 装配层的原因：响应里除门店资料还要带平台参数只读区（sys_config 的 Owner 就是 app），
 * store 模块按矩阵只依赖 common，拿不到它（ADR-020 §5）。
 * 平台参数**只读**：P8 §4.5 :267 规划的 ADMIN 专属 /admin/configs **至今无实现**（全仓无该路由），
 * 所以参数当前只能由平台侧运维直接改库——设置页不得让店长以为改了会生效（ADR-020 实现期订正）。
 */
@RestController
@RequestMapping("/api/v1/store/info")
public class StoreInfoController {

    private final StoreInfoApi storeInfoApi;
    private final PlatformParamQuery platformParamQuery;

    public StoreInfoController(StoreInfoApi storeInfoApi, PlatformParamQuery platformParamQuery) {
        this.storeInfoApi = storeInfoApi;
        this.platformParamQuery = platformParamQuery;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('perm:store:info:update')")
    public Result<StoreSettings> info() {
        StoreInfoView store = requireStore();
        return Result.ok(StoreSettings.builder()
                .store(store)
                .platformParams(platformParamQuery.list())
                .build());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('perm:store:info:update')")
    public Result<StoreInfoView> update(@RequestBody @Valid StoreInfoUpdate cmd) {
        StoreInfoView store = requireStore();
        return Result.ok(storeInfoApi.update(store.getId(), cmd));
    }

    /** 门店必须存在且属于当前上下文——storeCtx 已由登录人的 ACTIVE 归属校验过，这里只防上下文指向已删门店。 */
    private StoreInfoView requireStore() {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        StoreInfoView store = storeInfoApi.info(storeCtx);
        if (store == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        return store;
    }

    /** 设置页首屏：门店资料 + 平台参数只读区。 */
    @Data
    @Builder
    public static class StoreSettings {
        private StoreInfoView store;
        private List<PlatformParamQuery.PlatformParam> platformParams;
    }
}
