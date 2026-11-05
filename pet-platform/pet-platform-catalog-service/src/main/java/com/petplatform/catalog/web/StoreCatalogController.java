package com.petplatform.catalog.web;

import com.petplatform.catalog.api.dto.StoreServiceQuery;
import com.petplatform.catalog.api.dto.StoreServiceUpsertRequest;
import com.petplatform.catalog.api.dto.StoreServiceView;
import com.petplatform.catalog.service.StoreCatalogService;
import com.petplatform.common.result.PageResult;
import com.petplatform.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店端上架服务维护（第八阶段 §4.3 /api/v1/store/store-services）。
 * 须 X-Store-Id：storeId 由 Service 层从上下文取，入参里的 storeId 一律忽略。
 */
@RestController
@RequestMapping("/api/v1/store/store-services")
public class StoreCatalogController {

    private static final String MANAGE = "hasAuthority('perm:catalog:store_service:manage')";
    /** 代客建单只需读可订清单；该码不带到 POST/PUT，前台仍不能改价/上下架。 */
    private static final String APPOINTMENT_CREATE = "hasAuthority('perm:appointment:create')";

    private final StoreCatalogService storeCatalogService;

    public StoreCatalogController(StoreCatalogService storeCatalogService) {
        this.storeCatalogService = storeCatalogService;
    }

    @GetMapping
    @PreAuthorize(MANAGE)
    public Result<PageResult<StoreServiceView>> list(@Valid StoreServiceQuery query) {
        return Result.ok(storeCatalogService.page(query));
    }

    /**
     * 代客建单的可订服务清单（RECEPTIONIST/店长/ADMIN）：只读、只出本店 ON 上架行。
     * 不复用上方的 manage 闸，避免前台为建单获得价目维护读口；写侧仍严格 MANAGE。
     */
    @GetMapping("/bookable")
    @PreAuthorize(APPOINTMENT_CREATE)
    public Result<PageResult<StoreServiceView>> bookable() {
        return Result.ok(storeCatalogService.bookable());
    }

    @PostMapping
    @PreAuthorize(MANAGE)
    public Result<StoreServiceView> create(@Valid @RequestBody StoreServiceUpsertRequest request) {
        return Result.ok(storeCatalogService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(MANAGE)
    public Result<StoreServiceView> update(@PathVariable Long id,
                                          @Valid @RequestBody StoreServiceUpsertRequest request) {
        return Result.ok(storeCatalogService.update(id, request));
    }
}
