package com.petplatform.employee.web;

import com.petplatform.common.result.PageResult;
import com.petplatform.common.result.Result;
import com.petplatform.employee.api.dto.SkillQuery;
import com.petplatform.employee.api.dto.SkillUpsertRequest;
import com.petplatform.employee.api.dto.SkillView;
import com.petplatform.employee.service.SkillAdminService;
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
 * 平台端技能字典维护（第八阶段 §4.5）。ADMIN 的 storeCtx=PLATFORM_HQ，
 * 需带 X-Store-Id 头（JwtAuthFilter 据此装配门店权限码）。
 */
@RestController
@RequestMapping("/api/v1/admin/skills")
public class AdminSkillController {

    private static final String MANAGE = "hasAuthority('perm:catalog:skill:manage')";

    private final SkillAdminService skillAdminService;

    public AdminSkillController(SkillAdminService skillAdminService) {
        this.skillAdminService = skillAdminService;
    }

    @GetMapping
    @PreAuthorize(MANAGE)
    public Result<PageResult<SkillView>> list(@Valid SkillQuery query) {
        return Result.ok(skillAdminService.page(query));
    }

    @PostMapping
    @PreAuthorize(MANAGE)
    public Result<SkillView> create(@Valid @RequestBody SkillUpsertRequest request) {
        return Result.ok(skillAdminService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(MANAGE)
    public Result<SkillView> update(@PathVariable Long id, @Valid @RequestBody SkillUpsertRequest request) {
        return Result.ok(skillAdminService.update(id, request));
    }
}
