package com.petplatform.pet.service;

import com.petplatform.pet.api.PetQueryApi;
import com.petplatform.pet.api.dto.PetSummary;
import com.petplatform.pet.domain.PetEntity;
import com.petplatform.pet.mapper.PetMapper;
import com.petplatform.pet.mapper.PetQueryMapper;
import com.petplatform.common.query.LikePattern;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PetQueryService implements PetQueryApi {

    private final PetQueryMapper mapper;
    private final PetMapper petMapper;
    private final PetAvatarSigner avatarSigner;

    public PetQueryService(PetQueryMapper mapper, PetMapper petMapper, PetAvatarSigner avatarSigner) {
        this.mapper = mapper;
        this.petMapper = petMapper;
        this.avatarSigner = avatarSigner;
    }

    @Override
    public List<PetSummary> summaries(Collection<Long> petIds) {
        if (petIds == null || petIds.isEmpty()) {
            return List.of();
        }
        return mapper.selectSummaries(petIds).stream()
                .map(r -> PetSummary.builder()
                        .id(r.getId())
                        .name(r.getName())
                        .species(r.getSpecies())
                        .breed(r.getBreed())
                        .gender(r.getGender())
                        .avatar(r.getAvatar())
                        .status(r.getStatus())
                        .weightKg(r.getWeightKg())
                        .sizeCategory(r.getSizeCategory())
                        .birthday(r.getBirthday())
                        .build())
                .toList();
    }

    @Override
    public Map<Long, String> namesIncludingDeleted(Collection<Long> petIds) {
        if (petIds == null || petIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (PetQueryMapper.PetRow r : mapper.selectNamesIncludingDeleted(petIds)) {
            if (r.getName() != null && !r.getName().isBlank()) {
                names.put(r.getId(), r.getName());
            }
        }
        return names;
    }

    @Override
    public PetSummary lockForUse(Long petId) {
        if (petId == null) {
            return null;
        }
        PetEntity p = petMapper.selectByIdForUpdate(petId);
        if (p == null) {
            return null;
        }
        return PetSummary.builder()
                .id(p.getId()).name(p.getName()).species(p.getSpecies()).breed(p.getBreed())
                .gender(p.getGender()).avatar(p.getAvatar()).status(p.getStatus())
                .weightKg(p.getWeightKg()).sizeCategory(p.getSizeCategory()).birthday(p.getBirthday())
                .build();
    }

    @Override
    public Map<Long, String> avatarUrls(Collection<Long> petIds) {
        if (petIds == null || petIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> keys = new LinkedHashMap<>();
        for (PetSummary p : summaries(petIds)) {
            if (p.getAvatar() != null) {
                keys.put(p.getId(), p.getAvatar());
            }
        }
        return avatarSigner.signAll(keys);
    }

    /**
     * 关键词归一化收在这里：空白即不查（返回空集，调用方据此判「无命中」而不是「无过滤」），
     * limit 至少 1，避免 {@code LIMIT 0} 被误当成"匹配全部"。
     *
     * <p>本方法是**单批**取数（keyset 游标），完整遍历由调用方循环到"不满一批"为止——
     * 上限只约束一次往返的行数，不约束结果集。
     */
    @Override
    public List<Long> findPetIdsByName(String keyword, Long afterId, int limit) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty() || limit <= 0) {
            return List.of();
        }
        return mapper.selectIdsByNameLike(LikePattern.literal(kw), afterId, limit);
    }
}
