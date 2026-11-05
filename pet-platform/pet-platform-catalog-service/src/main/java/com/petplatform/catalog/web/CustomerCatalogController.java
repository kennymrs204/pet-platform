package com.petplatform.catalog.web;

import com.petplatform.catalog.api.dto.StoreServiceQuery;
import com.petplatform.catalog.api.dto.StoreServiceView;
import com.petplatform.catalog.service.CustomerCatalogService;
import com.petplatform.common.result.PageResult;
import com.petplatform.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端门店服务浏览（第八阶段 §4.2 GET /api/v1/customer/store-services?storeId=）。
 * 只出 ON 的上架服务；可约时段与可指定员工列表属 schedule 段（availability:view）。
 */
@RestController
@RequestMapping("/api/v1/customer/store-services")
public class CustomerCatalogController {

    private final CustomerCatalogService customerCatalogService;

    public CustomerCatalogController(CustomerCatalogService customerCatalogService) {
        this.customerCatalogService = customerCatalogService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('perm:catalog:browse')")
    public Result<PageResult<StoreServiceView>> list(@Valid StoreServiceQuery query) {
        return Result.ok(customerCatalogService.page(query));
    }
}
