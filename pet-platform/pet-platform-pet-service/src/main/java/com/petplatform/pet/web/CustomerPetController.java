package com.petplatform.pet.web;

import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.pet.api.dto.PetCreateRequest;
import com.petplatform.pet.api.dto.PetHealthUpdateRequest;
import com.petplatform.pet.api.dto.PetHealthVO;
import com.petplatform.pet.api.dto.PetUpdateRequest;
import com.petplatform.pet.api.dto.PetVO;
import com.petplatform.pet.service.PetService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * C 端宠物档案（第八阶段 §4.2 /api/v1/customer/pets，ADR-014）。
 * userId 只取认证上下文；归属校验在 Service 层显式做（@PreAuthorize 仅是第一道闸）。
 */
@RestController
@RequestMapping("/api/v1/customer/pets")
public class CustomerPetController {

    private final PetService petService;

    public CustomerPetController(PetService petService) {
        this.petService = petService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('perm:pet:view')")
    public Result<List<PetVO>> list() {
        return Result.ok(petService.listMine(currentUserId()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('perm:pet:create')")
    public Result<PetVO> create(@Valid @RequestBody PetCreateRequest request) {
        return Result.ok(petService.create(currentUserId(), null, request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('perm:pet:view')")
    public Result<PetVO> detail(@PathVariable Long id) {
        return Result.ok(petService.detail(currentUserId(), id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('perm:pet:update')")
    public Result<PetVO> update(@PathVariable Long id, @Valid @RequestBody PetUpdateRequest request) {
        return Result.ok(petService.update(currentUserId(), id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('perm:pet:delete')")
    public Result<Void> delete(@PathVariable Long id) {
        petService.delete(currentUserId(), id);
        return Result.ok();
    }

    @GetMapping("/{id}/health")
    @PreAuthorize("hasAuthority('perm:pet:health:view')")
    public Result<PetHealthVO> getHealth(@PathVariable Long id) {
        return Result.ok(petService.getHealth(currentUserId(), id));
    }

    @PutMapping("/{id}/health")
    @PreAuthorize("hasAuthority('perm:pet:health:manage')")
    public Result<PetHealthVO> updateHealth(@PathVariable Long id,
                                            @Valid @RequestBody PetHealthUpdateRequest request) {
        return Result.ok(petService.updateHealth(currentUserId(), id, request));
    }

    private Long currentUserId() {
        AuthPrincipal principal = CurrentUserHolder.require();
        return principal.userId();
    }
}
