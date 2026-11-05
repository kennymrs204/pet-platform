package com.petplatform.user.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.petplatform.common.error.BusinessException;
import com.petplatform.user.api.UserAccountApi;
import com.petplatform.user.api.UserErrorCode;
import com.petplatform.user.domain.UserEntity;
import com.petplatform.user.mapper.CustomerProfileMapper;
import com.petplatform.user.mapper.UserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAccountService implements UserAccountApi {

    private final UserMapper userMapper;
    private final CustomerProfileMapper customerProfileMapper;

    public UserAccountService(UserMapper userMapper, CustomerProfileMapper customerProfileMapper) {
        this.userMapper = userMapper;
        this.customerProfileMapper = customerProfileMapper;
    }

    @Override
    public String statusOf(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        return user == null ? null : user.getStatus();
    }

    @Override
    public boolean existsCustomerProfile(Long userId) {
        return customerProfileMapper.countByUserId(userId) > 0;
    }

    @Override
    public Long userIdByPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        return userMapper.selectIdByPhone(phone.trim());
    }

    /** 与 pet 侧同规则：空白即不查（空集=无命中，不是"无过滤"）；limit≤0 同样不查。单批 keyset 取数。 */
    @Override
    public java.util.List<Long> findUserIdsByNicknameOrPhone(String keyword, Long afterId, int limit) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty() || limit <= 0) {
            return java.util.List.of();
        }
        return userMapper.selectIdsByNicknameOrPhoneLike(
                com.petplatform.common.query.LikePattern.literal(kw), afterId, limit);
    }

    /**
     * 门店代客建档（ADR-057）。两行写入同一事务：usr_user（无密码）+ usr_customer_profile（带 origin_store_id）。
     * 20001 的语义在此处是**超集**："该手机号已在平台注册或建档"——本口只建新号码，不做账号关联。
     */
    @Override
    @Transactional
    public Long createStoreCustomer(String phone, String nickname, Long originStoreId) {
        String normalizedPhone = phone == null ? "" : phone.trim();
        if (userMapper.selectIdByPhone(normalizedPhone) != null) {
            throw new BusinessException(UserErrorCode.PHONE_REGISTERED, "该手机号已在平台注册或建档");
        }
        UserEntity user = new UserEntity();
        user.setPhone(normalizedPhone);
        user.setNickname(nickname == null ? "" : nickname.trim());
        user.setStatus("NORMAL");
        // 刻意不设 password_hash：顾客用验证码登录，或走 RESET 场景码设密码认领（ADR-057 决策 4）
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(UserErrorCode.PHONE_REGISTERED, "该手机号已在平台注册或建档");
        }
        customerProfileMapper.insertWithOrigin(IdWorker.getId(), user.getId(), originStoreId);
        return user.getId();
    }

    @Override
    public java.util.List<Long> customerUserIdsByOriginStore(Long storeId) {
        if (storeId == null || storeId == 0L) {
            return java.util.List.of();
        }
        return customerProfileMapper.selectUserIdsByOriginStore(storeId);
    }

    /**
     * 门店侧改档（ADR-058）：事务内定点更新三列。空白归一为 null（空=清空），
     * 不触碰 avatar/status/phone/preferred/marketing——见 {@link UserAccountApi#updateStoreCustomerProfile}。
     */
    @Override
    @Transactional
    public void updateStoreCustomerProfile(Long userId, String nickname, String contactPhone, String remark) {
        String n = nickname == null ? "" : nickname.trim();
        String contact = contactPhone == null || contactPhone.isBlank() ? null : contactPhone.trim();
        String note = remark == null || remark.isBlank() ? null : remark.trim();
        userMapper.updateNickname(userId, n);
        customerProfileMapper.updateContactAndRemark(userId, contact, note);
    }

    @Override
    public java.util.List<com.petplatform.user.api.dto.UserProfileView> profilesByIds(java.util.Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return java.util.List.of();
        }
        java.util.Map<Long, CustomerProfileMapper.ProfileRow> byUser = new java.util.HashMap<>();
        customerProfileMapper.selectByUserIds(userIds).forEach(p -> byUser.put(p.getUserId(), p));
        return userMapper.selectBatchIds(userIds).stream()
                .map(u -> {
                    CustomerProfileMapper.ProfileRow p = byUser.get(u.getId());
                    return com.petplatform.user.api.dto.UserProfileView.builder()
                            .userId(u.getId())
                            .nickname(u.getNickname())
                            .avatar(u.getAvatar())
                            .phone(u.getPhone())
                            .status(u.getStatus())
                            .registeredAt(u.getCreatedAt())
                            .preferredStoreId(p == null ? null : p.getPreferredStoreId())
                            .marketingOptIn(p == null ? null : p.isMarketingOptIn())
                            .defaultContactPhone(p == null ? null : p.getDefaultContactPhone())
                            .remark(p == null ? null : p.getRemark())
                            .build();
                })
                .toList();
    }
}
