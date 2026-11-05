package com.petplatform.user.api;

import java.util.List;

/**
 * 用户-宠物共管关联（表 Owner：user 模块，usr_user_pet）。
 * pet 模块经本 API 完成归属校验与关系维护，禁止直查表（第二阶段 §3.2 + ADR-014 矩阵增量）。
 */
public interface UserPetApi {

    /** 用户与该宠物是否存在活跃关联（OWNER/FAMILY 均算可管理）。 */
    boolean canManage(Long userId, Long petId);

    /** 用户可管理的宠物 ID 列表（活跃关联）；无关联返回空列表。 */
    List<Long> manageablePetIds(Long userId);

    /** 建立 OWNER 关联；已存在活跃关联时幂等跳过（并发唯一键冲突亦视为成功）。 */
    void grantOwner(Long userId, Long petId);

    /** 宠物删除时逻辑删除其全部用户关联。 */
    void removeAllRelations(Long petId);

    /**
     * 批量取多个用户的活跃宠物关联（ADR-017 §6，B 端客户视图数据源：客户名下宠物与"宠物名"关键词命中）。
     * userId → petIds（关联表顺序不保证，调用方按 petId 排序后再出参）；无关联的 userId 不入 Map。
     */
    java.util.Map<Long, java.util.List<Long>> petIdsByOwners(java.util.Collection<Long> userIds);

    /**
     * 批量取宠物的**主登记人**（relation='OWNER'），供 B 端反查"这只宠物是谁的"（托管列表客户字段）。
     * petId → userId；同一宠物若有多条 OWNER 取 user_id 最小者（结果确定，不随查询顺序漂移）；
     * FAMILY 共管不算主登记人。无 OWNER 行的 petId 不入 Map——调用方据此显示"主人未登记"，不得伪造归属。
     */
    java.util.Map<Long, Long> primaryOwnerIds(java.util.Collection<Long> petIds);
}
