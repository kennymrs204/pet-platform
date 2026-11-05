package com.petplatform.pet.service;

import com.petplatform.pet.api.PetCommandApi;
import com.petplatform.pet.api.dto.PetBaseUpdateRequest;
import com.petplatform.pet.api.dto.PetCreateRequest;
import com.petplatform.pet.api.dto.PetHealthActor;
import com.petplatform.pet.api.dto.PetHealthUpdateRequest;
import com.petplatform.pet.api.dto.PetHealthVO;
import com.petplatform.pet.api.dto.PetVO;
import org.springframework.stereotype.Service;

/**
 * 宠物写侧端口实现（ADR-058）：直接委托 {@link PetService#create(Long, PetCreateRequest)}。
 * 该方法的 owner 本就是入参（C 端 controller 传当前登录者），B 端代建传目标客户 userId
 * 即复用同一条校验/尺寸计算/健康空档/关系授予/事件发布路径——本类不含任何业务分支。
 */
@Service
public class PetCommandService implements PetCommandApi {

    private final PetService petService;

    public PetCommandService(PetService petService) {
        this.petService = petService;
    }

    @Override
    public PetVO createForOwner(Long ownerUserId, Long originStoreId, PetCreateRequest request) {
        return petService.create(ownerUserId, originStoreId, request);
    }

    @Override
    public PetVO updateBaseForOwner(Long ownerUserId, Long petId, PetBaseUpdateRequest request) {
        return petService.updateBase(ownerUserId, petId, request);
    }

    @Override
    public PetHealthVO updateHealthForActor(Long petId, PetHealthActor actor, PetHealthUpdateRequest request) {
        return petService.updateHealthForActor(petId, actor, request);
    }

    @Override
    public void deleteForStore(Long petId) {
        petService.deleteForStore(petId);
    }
}
