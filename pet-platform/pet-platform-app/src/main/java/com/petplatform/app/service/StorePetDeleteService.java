package com.petplatform.app.service;

import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.pet.api.PetCommandApi;
import com.petplatform.pet.service.StorePetQueryService;
import org.springframework.stereotype.Service;

/**
 * B 端宠物删除编排。
 *
 * <p>当前门店可见性/门店隔离复用 {@link StorePetQueryService#requireVisible}；
 * 预约/托管阻断、宠物行锁与逻辑删除由 pet 模块同一事务完成。
 * 客户端不提供 storeId 或操作人身份；历史业务数据不级联删除。
 */
@Service
public class StorePetDeleteService {

    private final StorePetQueryService storePetQueryService;
    private final PetCommandApi petCommandApi;

    public StorePetDeleteService(StorePetQueryService storePetQueryService, PetCommandApi petCommandApi) {
        this.storePetQueryService = storePetQueryService;
        this.petCommandApi = petCommandApi;
    }

    public void delete(Long storeId, AuthPrincipal principal, Long petId) {
        storePetQueryService.requireVisible(storeId, principal, petId);
        petCommandApi.deleteForStore(petId);
    }
}
