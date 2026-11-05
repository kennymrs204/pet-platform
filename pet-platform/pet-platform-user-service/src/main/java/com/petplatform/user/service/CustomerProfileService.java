package com.petplatform.user.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.petplatform.user.api.dto.CustomerProfileView;
import com.petplatform.user.api.dto.ProfileUpdateRequest;
import com.petplatform.user.domain.UserEntity;
import com.petplatform.user.mapper.CustomerProfileMapper;
import com.petplatform.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C 端账号资料（P8 §4.2 GET/PUT /customer/profile，ADR-027）。
 * GET 对无档案行 ensure 建档（补齐"注册未建 profile → CUSTOMER 码缺失"的历史面；
 * 当前权限缓存需下次装配才并入 CUSTOMER 码——ADR-027 §5 记录时点）。
 */
@Service
public class CustomerProfileService {

    private final UserMapper userMapper;
    private final CustomerProfileMapper profileMapper;
    private final org.springframework.beans.factory.ObjectProvider<com.petplatform.common.storage.MediaStorage> media;
    private final long maxAvatarBytes;

    public CustomerProfileService(UserMapper userMapper, CustomerProfileMapper profileMapper,
                                  org.springframework.beans.factory.ObjectProvider<com.petplatform.common.storage.MediaStorage> media,
                                  @org.springframework.beans.factory.annotation.Value("${pet.storage.max-size-mb:5}") long maxSizeMb) {
        this.userMapper = userMapper;
        this.profileMapper = profileMapper;
        this.media = media;
        this.maxAvatarBytes = maxSizeMb * 1024 * 1024;
    }

    public CustomerProfileView get(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        ensureProfile(userId);
        return toView(user, profileMapper.selectEditRow(userId));
    }

    @Transactional
    public CustomerProfileView update(Long userId, ProfileUpdateRequest req) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        String nickname = req.getNickname() == null ? "" : req.getNickname().trim();
        String avatar = req.getAvatar() == null || req.getAvatar().isBlank() ? null : req.getAvatar().trim();
        // 先落实际对象闸，再做任何数据库写（含 ensureProfile 的建档写入）。
        if (avatar != null) {
            com.petplatform.common.storage.MediaObjectGuard.requireWritable(
                    media.getIfAvailable(), avatar, userId, "USER_AVATAR", maxAvatarBytes);
        }
        ensureProfile(userId);
        String contact = req.getDefaultContactPhone() == null || req.getDefaultContactPhone().isBlank()
                ? null : req.getDefaultContactPhone().trim();
        userMapper.updateNicknameAvatar(userId, nickname, avatar);
        profileMapper.updateEditRow(userId, contact, req.getPreferredStoreId(),
                Boolean.TRUE.equals(req.getMarketingOptIn()));
        return toView(userMapper.selectById(userId), profileMapper.selectEditRow(userId));
    }

    /** 建档幂等（注册与 GET 共用；uk_user + INSERT IGNORE 兜并发）。 */
    public void ensureProfile(Long userId) {
        if (profileMapper.countByUserId(userId) == 0) {
            profileMapper.insertIgnore(IdWorker.getId(), userId);
        }
    }

    private CustomerProfileView toView(UserEntity u, CustomerProfileMapper.EditRow p) {
        com.petplatform.common.storage.MediaStorage storage = media.getIfAvailable();
        return CustomerProfileView.builder()
                .userId(u.getId()).nickname(u.getNickname()).avatar(u.getAvatar())
                .avatarUrl(signAvatar(storage, u))
                .phone(u.getPhone()).status(u.getStatus()).registeredAt(u.getCreatedAt())
                .defaultContactPhone(p == null ? null : p.getDefaultContactPhone())
                .preferredStoreId(p == null ? null : p.getPreferredStoreId())
                .marketingOptIn(p == null || p.isMarketingOptIn())
                .build();
    }

    /** 头像签名归属闸（ADR-029 §契约4）：avatar 用户自填，只签"桶主=本人"的 key，其余回 null 不签。 */
    private String signAvatar(com.petplatform.common.storage.MediaStorage storage, UserEntity u) {
        if (storage == null || u.getAvatar() == null) {
            return null;
        }
        Long owner = com.petplatform.common.storage.MediaStorage.keyOwner(u.getAvatar());
        return owner != null && owner.equals(u.getId())
                ? storage.presignGet(u.getAvatar(), com.petplatform.common.storage.MediaStorage.GET_TTL)
                : null;
    }
}
