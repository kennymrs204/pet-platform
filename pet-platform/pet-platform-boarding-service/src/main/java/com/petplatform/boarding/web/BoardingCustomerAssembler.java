package com.petplatform.boarding.web;

import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.user.api.UserAccountApi;
import com.petplatform.user.api.UserPetApi;
import com.petplatform.user.api.dto.UserProfileView;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * B 端托管域的「客户列」装配（ADR-028 口径，住单列表与待支付看板共用）。
 *
 * <p>数据链：petId → {@code usr_user_pet}(relation='OWNER') 主登记人 → {@code usr_user} 昵称/手机号，
 * 全程经 user-api——boarding 不直查 {@code usr_*} 表（表 Owner 边界，ADR-015 §6 既有依赖边）。
 *
 * <p>口径：
 * <ul>
 *   <li>无 {@code customer:view:store}（护理员/医生等任务角色）→ 返回空 Map，整列不出现在响应里，
 *       与 ADR-017 §2「任务角色不应看到客户身份与联系方式」一致；</li>
 *   <li>有权限但宠物无 OWNER 关联（或仅有 FAMILY 共管）→ 条目内三字段均为 null，
 *       前端如实标"未登记"，不伪造归属；</li>
 *   <li>昵称为空或主登记人账号已注销 → "顾客{userId}"（与客户列表同款兜底）；</li>
 *   <li>手机号一律服务端脱敏后出参，完整号永不下发。</li>
 * </ul>
 */
@Component
public class BoardingCustomerAssembler {

    /** 客户可见性码（ADR-017 §2 / V3 种子：ADMIN、STORE_MANAGER、RECEPTIONIST）——任务角色不含 */
    static final String CODE_CUSTOMER_VIEW = "customer:view:store";

    private final UserPetApi userPetApi;
    private final UserAccountApi userAccountApi;

    public BoardingCustomerAssembler(UserPetApi userPetApi, UserAccountApi userAccountApi) {
        this.userPetApi = userPetApi;
        this.userAccountApi = userAccountApi;
    }

    /** 批量装配 petId → 客户引用；整页最多两次查询（一次 petIds、一次 userIds），无逐行 N+1。 */
    public Map<Long, CustomerRef> byPet(List<Long> petIds, AuthPrincipal principal) {
        if (petIds.isEmpty() || !principal.permissions().contains(CODE_CUSTOMER_VIEW)) {
            return Map.of();
        }
        Map<Long, Long> ownerByPet = userPetApi.primaryOwnerIds(petIds);
        Map<Long, UserProfileView> profiles = ownerByPet.isEmpty() ? Map.of()
                : userAccountApi.profilesByIds(ownerByPet.values()).stream()
                .collect(Collectors.toMap(UserProfileView::getUserId, Function.identity(), (a, b) -> a));
        Map<Long, CustomerRef> byPet = new HashMap<>();
        for (Long petId : petIds) {
            Long userId = ownerByPet.get(petId);
            UserProfileView profile = userId == null ? null : profiles.get(userId);
            String nickname = userId == null ? null
                    : profile == null || profile.getNickname() == null || profile.getNickname().isBlank()
                    ? "顾客" + userId : profile.getNickname();
            byPet.put(petId, CustomerRef.builder()
                    .userId(userId).nickname(nickname)
                    .phoneMasked(userId == null ? null : mask(profile == null ? null : profile.getPhone()))
                    .build());
        }
        return byPet;
    }

    /**
     * 服务端脱敏（ADR-017 §3：保留前 3 后 4，异常长度全遮不抛错）。
     * 与 app 层客户视图那份规则相同但刻意各留一份：boarding 不能依赖 app（ArchUnit「nobody depends on app」），
     * 真要收敛应连 C 端一起收到 common 的 PII 工具，而不是只服务这一个字段。
     */
    static String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone == null ? "" : "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** 客户引用（只出展示字段：昵称 + 脱敏手机号；完整号与消费额不经本域端点，ADR-017 §3）。 */
    @Data
    @Builder
    public static class CustomerRef {
        private Long userId;
        private String nickname;
        private String phoneMasked;
    }
}
