package com.petplatform.pet.api;

import com.petplatform.pet.api.dto.PetBaseUpdateRequest;
import com.petplatform.pet.api.dto.PetCreateRequest;
import com.petplatform.pet.api.dto.PetHealthActor;
import com.petplatform.pet.api.dto.PetHealthUpdateRequest;
import com.petplatform.pet.api.dto.PetHealthVO;
import com.petplatform.pet.api.dto.PetVO;

/**
 * 宠物档案写侧端口（表 Owner：pet 模块；ADR-058 新增）。
 * 供 app 装配层的"门店代客建档宠物"场景调用——宠物域自身不感知"门店/客户"概念，
 * owner 由调用方给定（C 端传当前登录者，B 端传目标客户 userId）。
 */
public interface PetCommandApi {

    /**
     * 为指定 owner 创建宠物档案（name/species 等校验、尺寸按体重计算、健康空档、owner 关系、
     * PetCreatedEvent 均与 C 端创建同一条路径——本口只是把 owner 参数化，不复制业务规则）。
     *
     * @param originStoreId 代建门店（ADR-058 增量）：B 端门店代建传该店 id（作为该店"可见范围"的第三来源）；
     *                      C 端或非门店场景传 null
     */
    PetVO createForOwner(Long ownerUserId, Long originStoreId, PetCreateRequest request);

    /**
     * 按已校验的 owner 更新宠物基础资料（B 端受限编辑）。
     * 只更新姓名/物种/品种/性别/绝育/生日/体重/备注；avatar/status/owner/origin/vaccine/sizeCategory
     * 不由本命令直接接收，服务端保留或重算。
     */
    PetVO updateBaseForOwner(Long ownerUserId, Long petId, PetBaseUpdateRequest request);

    /**
     * 按已校验的操作人更新健康档案（B 端门店维护）。
     * 门店可见性、目标宠物归属由 app 装配层先行校验；本口只保证宠物存在，并记录
     * before/after 变更日志。actor 必须由服务端登录主体构造，客户端不可传入。
     */
    PetHealthVO updateHealthForActor(Long petId, PetHealthActor actor, PetHealthUpdateRequest request);

    /**
     * B 端删除宠物：调用方先完成当前门店可见性校验。本命令在事务内锁宠物/健康行，
     * 并全局检查未完成预约/托管；任一存在则拒绝。历史业务数据不级联删除。
     */
    void deleteForStore(Long petId);
}
