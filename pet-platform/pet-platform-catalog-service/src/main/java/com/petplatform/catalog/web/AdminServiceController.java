package com.petplatform.catalog.web;

import com.petplatform.catalog.api.dto.ServiceQuery;
import com.petplatform.catalog.api.dto.ServiceUpsertRequest;
import com.petplatform.catalog.api.dto.ServiceView;
import com.petplatform.catalog.service.AdminCatalogService;
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
 * 平台端服务目录维护（第八阶段 §4.5）。ADMIN 的 storeCtx=PLATFORM_HQ，
 * 需带 X-Store-Id 头（JwtAuthFilter 据此装配门店权限码）。
 */
@RestController
@RequestMapping("/api/v1/admin/services")
public class AdminServiceController {

    private static final String MANAGE = "hasAuthority('perm:catalog:service:manage')";

    private final AdminCatalogService adminCatalogService;

    public AdminServiceController(AdminCatalogService adminCatalogService) {
        this.adminCatalogService = adminCatalogService;
    }

    @GetMapping
    @PreAuthorize(MANAGE)
    public Result<PageResult<ServiceView>> list(@Valid ServiceQuery query) {
        return Result.ok(adminCatalogService.page(query));
    }

    @PostMapping
    @PreAuthorize(MANAGE)
    public Result<ServiceView> create(@Valid @RequestBody ServiceUpsertRequest request) {
        return Result.ok(adminCatalogService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(MANAGE)
    public Result<ServiceView> update(@PathVariable Long id, @Valid @RequestBody ServiceUpsertRequest request) {
        return Result.ok(adminCatalogService.update(id, request));
    }
}
