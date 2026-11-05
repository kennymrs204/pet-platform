package com.petplatform.app.web;

import com.petplatform.catalog.api.CatalogQueryApi;
import com.petplatform.catalog.api.dto.StoreServiceBrief;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.Result;
import com.petplatform.store.api.StoreQueryApi;
import com.petplatform.store.api.dto.PublicStoreView;
import lombok.Builder;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * C 端公开门店浏览（store 第二段，ADR-022）：GET /api/v1/public/stores[?keyword=] 与 /{id}。
 *
 * 放 app 装配层的原因：第二阶段 §3.2 矩阵 store 行仅依赖 common（store→catalog ✗），
 * 而本端点必须组合 sto_store（store）× cat_store_service/cat_service（catalog）——
 * 唯一合法组合根是 app（StoreStatsController/PermissionAssembler 先例），矩阵零新增边。
 *
 * 匿名可访问（SecurityConfig permitAll `/api/v1/public/**`，无 @PreAuthorize）；
 * 出参形状对齐 c-web api/store.ts PublicStoreVO[]（外层纯数组，ADR-022 §1 订正 ADR-010 §1）。
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicStoreController {

    private final StoreQueryApi storeApi;
    private final CatalogQueryApi catalogApi;

    public PublicStoreController(StoreQueryApi storeApi, CatalogQueryApi catalogApi) {
        this.storeApi = storeApi;
        this.catalogApi = catalogApi;
    }

    @GetMapping("/stores")
    public Result<List<PublicStoreVO>> list(@RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) Integer pageNum,
                                            @RequestParam(required = false) Integer pageSize) {
        if (pageNum != null || pageSize != null) {
            // ADR-022 §1：本期不分页，出现即拒而非静默忽略，防前端误以为已分页
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "公开门店列表本期不支持分页");
        }
        return Result.ok(assemble(storeApi.publicStores(keyword)));
    }

    @GetMapping("/stores/{id}")
    public Result<PublicStoreVO> detail(@PathVariable Long id) {
        PublicStoreView store = storeApi.publicStore(id);
        if (store == null) {
            // 不可见（非 OPEN/总店/已删/不存在）统一 10004，不回显存在性
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        return Result.ok(assemble(List.of(store)).get(0));
    }

    private List<PublicStoreVO> assemble(List<PublicStoreView> stores) {
        if (stores.isEmpty()) {
            return List.of();
        }
        Map<Long, List<StoreServiceBrief>> byStore = catalogApi.activeServicesOfStores(
                        stores.stream().map(PublicStoreView::getId).toList()).stream()
                .collect(Collectors.groupingBy(StoreServiceBrief::getStoreId));
        return stores.stream().map(s -> PublicStoreVO.builder()
                .id(s.getId()).name(s.getName()).address(s.getAddress())
                .phone(s.getPhone()).status(s.getStatus())
                .services(byStore.getOrDefault(s.getId(), List.of()).stream()
                        .map(b -> ServiceVO.builder()
                                .serviceId(b.getStoreServiceId()).name(b.getName())
                                .category(b.getCategory()).price(b.getPrice())
                                .durationMin(b.getDurationMin()).build())
                        .toList())
                .build()).toList();
    }

    /** 出参块①：门店（字段=前端 PublicStoreVO，勿改名）。 */
    @Data
    @Builder
    public static class PublicStoreVO {
        private Long id;
        private String name;
        private String address;
        private String phone;
        private String status;
        private List<ServiceVO> services;
    }

    /** 出参块②：在架服务行；serviceId=storeServiceId（预约入口消费此 id，ADR-022 §4）。 */
    @Data
    @Builder
    public static class ServiceVO {
        private Long serviceId;
        private String name;
        private String category;
        private Long price;
        private Integer durationMin;
    }
}
