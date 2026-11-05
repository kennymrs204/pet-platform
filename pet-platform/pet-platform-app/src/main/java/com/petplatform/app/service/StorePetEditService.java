package com.petplatform.app.service;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.pet.api.PetCommandApi;
import com.petplatform.pet.api.PetErrorCode;
import com.petplatform.pet.api.dto.PetBaseUpdateRequest;
import com.petplatform.pet.api.dto.PetVO;
import com.petplatform.pet.service.StorePetQueryService;
import com.petplatform.user.api.UserPetApi;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * B 端宠物基础资料编辑编排。
 *
 * <p>顺序固定为：宠物可见性（现有 StorePetQueryService）→ OWNER 解析
 * （UserPetApi.primaryOwnerIds）→ OWNER 必须属于当前门店客户集（现有
 * StoreCustomerQueryService.requireCustomer）→ 受限基础资料更新命令。
 * 客户端不提供 owner/storeId；服务端只使用已验证的当前门店上下文和目标客户。
 *
 * <p>已知边界：宠物基础资料是全局数据。同一客户同时在多家门店客户集内时，
 * 多个满足权限与归属条件的门店仍可编辑同一份资料；本批次不新增主门店字段。
 */
@Service
public class StorePetEditService {

    private final StorePetQueryService storePetQueryService;
    private final UserPetApi userPetApi;
    private final StoreCustomerQueryService customerQueryService;
    private final PetCommandApi petCommandApi;

    public StorePetEditService(StorePetQueryService storePetQueryService, UserPetApi userPetApi,
                               StoreCustomerQueryService customerQueryService, PetCommandApi petCommandApi) {
        this.storePetQueryService = storePetQueryService;
        this.userPetApi = userPetApi;
        this.customerQueryService = customerQueryService;
        this.petCommandApi = petCommandApi;
    }

    public PetVO update(Long storeId, AuthPrincipal principal, Long petId, PetBaseUpdateRequest request) {
        storePetQueryService.requireVisible(storeId, principal, petId);

        Long ownerUserId = userPetApi.primaryOwnerIds(List.of(petId)).get(petId);
        if (ownerUserId == null) {
            // 没有 OWNER 就不能确认可编辑归属；与不可见同码，避免暴露归属细节
            throw BusinessException.of(PetErrorCode.PET_FORBIDDEN);
        }
        // 不在本店客户集 → 10004（沿用 ADR-058 的防枚举口径）
        customerQueryService.requireCustomer(storeId, ownerUserId);
        return petCommandApi.updateBaseForOwner(ownerUserId, petId, request);
    }
}
