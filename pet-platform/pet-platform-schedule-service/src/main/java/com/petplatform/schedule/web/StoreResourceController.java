package com.petplatform.schedule.web;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.schedule.api.StoreResourceApi;
import com.petplatform.schedule.api.dto.KennelResourceView;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端门店资源台账（P8 §4.3 :223，权限码 resource:manage：ADMIN/店长）。
 * 无 DELETE：占用行指向资源，停用即 status=DISABLED（ADR-020 §4）。
 */
@RestController
@RequestMapping("/api/v1/store/resources")
public class StoreResourceController {

    private final StoreResourceApi storeResourceApi;

    public StoreResourceController(StoreResourceApi storeResourceApi) {
        this.storeResourceApi = storeResourceApi;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('perm:resource:manage')")
    public Result<List<KennelResourceView>> list() {
        return Result.ok(storeResourceApi.listInStore(requireStoreCtx()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('perm:resource:manage')")
    public Result<KennelResourceView> create(@RequestBody ResourceCreateRequest body) {
        return Result.ok(storeResourceApi.create(requireStoreCtx(), body.getType(), body.getName()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('perm:resource:manage')")
    public Result<KennelResourceView> update(@PathVariable Long id, @RequestBody ResourceUpdateRequest body) {
        KennelResourceView updated = storeResourceApi.update(requireStoreCtx(), id, body.getName(), body.getStatus());
        if (updated == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        return Result.ok(updated);
    }

    private Long requireStoreCtx() {
        AuthPrincipal principal = CurrentUserHolder.require();
        Long storeCtx = principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        return storeCtx;
    }

    @Data
    public static class ResourceCreateRequest {
        private String type;
        private String name;
    }

    /** 两个字段都可省略（省略=不改）；全空即空操作，服务端不报错。 */
    @Data
    public static class ResourceUpdateRequest {
        private String name;
        private String status;
    }
}
