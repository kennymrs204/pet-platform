package com.petplatform.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.petplatform.user.api.UserPetApi;
import com.petplatform.user.domain.UserPetEntity;
import com.petplatform.user.mapper.UserPetMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * usr_user_pet 读写（表 Owner，ADR-014）。relation 仅 OWNER：
 * FAMILY 共管邀请（pet:relation:manage 端点）属后续段，本服务只建/查/清。
 */
@Slf4j
@Service
public class UserPetService implements UserPetApi {

    private final UserPetMapper mapper;

    public UserPetService(UserPetMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean canManage(Long userId, Long petId) {
        if (userId == null || petId == null) {
            return false;
        }
        return mapper.exists(Wrappers.<UserPetEntity>lambdaQuery()
                .eq(UserPetEntity::getUserId, userId)
                .eq(UserPetEntity::getPetId, petId));
    }

    @Override
    public List<Long> manageablePetIds(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return mapper.selectList(Wrappers.<UserPetEntity>lambdaQuery()
                        .select(UserPetEntity::getPetId)
                        .eq(UserPetEntity::getUserId, userId))
                .stream().map(UserPetEntity::getPetId).toList();
    }

    @Override
    public java.util.Map<Long, List<Long>> petIdsByOwners(java.util.Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Long, List<Long>> byOwner = new java.util.LinkedHashMap<>();
        mapper.selectList(Wrappers.<UserPetEntity>lambdaQuery()
                        .select(UserPetEntity::getUserId, UserPetEntity::getPetId)
                        .in(UserPetEntity::getUserId, userIds)
                        .orderByAsc(UserPetEntity::getPetId))
                .forEach(rel -> byOwner.computeIfAbsent(rel.getUserId(), k -> new java.util.ArrayList<>())
                        .add(rel.getPetId()));
        return byOwner;
    }

    @Override
    public java.util.Map<Long, Long> primaryOwnerIds(java.util.Collection<Long> petIds) {
        if (petIds == null || petIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Long, Long> ownerByPet = new java.util.HashMap<>();
        mapper.selectList(Wrappers.<UserPetEntity>lambdaQuery()
                        .select(UserPetEntity::getPetId, UserPetEntity::getUserId)
                        .in(UserPetEntity::getPetId, petIds)
                        .eq(UserPetEntity::getRelation, "OWNER")
                        .orderByAsc(UserPetEntity::getUserId))
                .forEach(rel -> ownerByPet.putIfAbsent(rel.getPetId(), rel.getUserId()));
        return ownerByPet;
    }

    @Override
    @Transactional
    public void grantOwner(Long userId, Long petId) {
        if (canManage(userId, petId)) {
            return;
        }
        UserPetEntity rel = new UserPetEntity();
        rel.setUserId(userId);
        rel.setPetId(petId);
        rel.setRelation("OWNER");
        try {
            mapper.insert(rel);
        } catch (DuplicateKeyException e) {
            // 并发建档撞 uk_user_pet：已有活跃关联，幂等成功
            log.debug("grantOwner 并发幂等 userId={} petId={}", userId, petId);
        }
    }

    @Override
    @Transactional
    public void removeAllRelations(Long petId) {
        mapper.delete(Wrappers.<UserPetEntity>lambdaQuery().eq(UserPetEntity::getPetId, petId));
    }
}
