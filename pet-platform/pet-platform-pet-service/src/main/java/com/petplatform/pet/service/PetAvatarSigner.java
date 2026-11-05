package com.petplatform.pet.service;

import com.petplatform.common.storage.MediaStorage;
import com.petplatform.user.api.UserPetApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 宠物头像 presigned GET 的统一出口（ADR-029 §契约4）。
 *
 * <p>归属闸：key 的桶主必须确实能管理这只宠物（{@code keyOwner(key)} 且 {@code canManage(owner, petId)}）才签。
 * avatar 是用户自填字段，而 presigned GET 一经签发即等于该对象的可读凭证 —— 所以"填一个别人的 key"
 * 换不到签名，跨宠物/跨用户串图同样签不出来。
 *
 * <p>返回 null / 不入 Map 的三种情形：{@code pet.storage.enabled=false}（实现 bean 不存在）、
 * key 为空或形态非法、SDK 签名抛错（如桶 region 探测失败）。视图侧一律按"没有可读地址"处理，回退占位图。
 *
 * <p>注：app 层 {@code StoreCustomerQueryService.signPetAvatar} 是同一规则的本地实现（该文件属并行会话在途改动，
 * 未一并收敛）；新代码请走本出口。
 */
@Slf4j
@Component
public class PetAvatarSigner {

    private final ObjectProvider<MediaStorage> media;
    private final UserPetApi userPetApi;

    public PetAvatarSigner(ObjectProvider<MediaStorage> media, UserPetApi userPetApi) {
        this.media = media;
        this.userPetApi = userPetApi;
    }

    /** 批量签名。入参 petId → 裸对象 key；返回 petId → presigned GET，签不出的 id 不入结果。 */
    public Map<Long, String> signAll(Map<Long, String> petIdToAvatarKey) {
        MediaStorage storage = media.getIfAvailable();
        if (storage == null || petIdToAvatarKey == null || petIdToAvatarKey.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> urls = new LinkedHashMap<>();
        petIdToAvatarKey.forEach((petId, key) -> {
            Long owner = MediaStorage.keyOwner(key);
            if (petId == null || owner == null || !userPetApi.canManage(owner, petId)) {
                return;
            }
            try {
                String url = storage.presignGet(key, MediaStorage.GET_TTL);
                if (url != null) {
                    urls.put(petId, url);
                }
            } catch (RuntimeException e) {
                log.warn("宠物头像签名失败，降级为无地址 petId={}: {}", petId, e.getMessage());
            }
        });
        return urls;
    }

    /** 单个签名（详情视图用）；无 key 或签不出返回 null。 */
    public String sign(Long petId, String avatarKey) {
        if (petId == null || avatarKey == null) {
            return null;
        }
        return signAll(Map.of(petId, avatarKey)).get(petId);
    }
}
