package com.petplatform.app.web;

import com.petplatform.common.result.Result;
import com.petplatform.store.api.StoreQueryApi;
import com.petplatform.store.api.dto.PublicStoreView;
import lombok.Builder;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台侧门店列表（ADR-044）：给"ADMIN 可代入任意门店"提供候选集，是店下拉的数据源。
 *
 * <p>为什么不塞进 {@code GET /auth/employee-identities}：那个端点的语义是"**我的** ACTIVE 归属"
 * （ADR-012 连排序契约都写死了，且有既有消费方），往里加"如果你是 ADMIN 就返回全网"会让一个端点
 * 有两种解释——前端恢复会话时那句"storeId 必须 ∈ ACTIVE 归属"的校验会立刻把自己拒掉。
 *
 * <p>闸用 {@code store:manage}：V2 种子实测该码**只有 ADMIN 持有**（店长/前台都没有），
 * 所以本端点不会把全网门店名单泄露给某家店的店长——那等于跨店存在性泄露。
 *
 * <p>出参刻意只给 id/name/status：地址与电话在 {@code /public/stores} 已经公开，这里不重复出，
 * 下拉也不需要；少一个字段将来就少一处要维护的口径。
 */
@RestController
@RequestMapping("/api/v1/admin/stores")
public class AdminStoreController {

    private final StoreQueryApi storeQueryApi;

    public AdminStoreController(StoreQueryApi storeQueryApi) {
        this.storeQueryApi = storeQueryApi;
    }

    /** 可代入的门店 = OPEN ∧ 非总部虚拟店 ∧ 未删，id 升序（与 {@code /public/stores} 同一过滤口径，不另发明一套）。 */
    @GetMapping
    @PreAuthorize("hasAuthority('perm:store:manage')")
    public Result<List<AdminStoreView>> list() {
        List<AdminStoreView> out = storeQueryApi.publicStores(null).stream()
                .map(s -> AdminStoreView.builder()
                        .storeId(s.getId()).name(s.getName()).status(s.getStatus()).build())
                .toList();
        return Result.ok(out);
    }

    /** 下拉用最小投影。 */
    @Data
    @Builder
    public static class AdminStoreView {
        private Long storeId;
        private String name;
        private String status;
    }
}
