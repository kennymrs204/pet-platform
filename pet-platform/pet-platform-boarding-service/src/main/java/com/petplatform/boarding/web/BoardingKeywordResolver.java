package com.petplatform.boarding.web;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.pet.api.PetQueryApi;
import com.petplatform.user.api.UserAccountApi;
import com.petplatform.user.api.UserPetApi;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 关键词解析（V3.1）：把「宠物名 / 客户昵称 / 手机号」这些**不属于 boarding 的名字**
 * 交给各自的 Owner 模块去匹配，boarding 只收下 id 集合。
 *
 * <p><b>候选集合是完整的，没有结果上限。</b>上一版给宠物名/客户各设了 500/200 的"命中上限"，
 * 命中更多时会静默丢掉后面的 id——该被搜到的住单搜不到，界面还不给任何提示。
 * 现在两侧都用 keyset 游标（{@code id > 上一批最大 id}）循环到「本批不满一批」为止，
 * 关联表同样分批。唯一保留的"分片"是 {@code BoardingQueryService} 为了限制单条 SQL 的 IN 长度
 * 而做的**互斥切片**：片与片不重不漏，相加就是全集（片数随候选数增长，不是上限）。
 *
 * <p>为什么不把名字匹配并进 boarding 的 SQL（EXISTS 子查询）：那要直查 {@code pet_pet}/{@code usr_user}，
 * 全仓没有任何跨模块直查先例，且违反表 Owner 边界（第二阶段 §3.2 依赖矩阵、
 * {@link BoardingBoardAssembler} 的「取名一律走 api 批量接口，不在 SQL 里 join 他模块表」）。
 * ADR-032 §3 担心的"第二套真相"指的是把名字复制/缓存在 boarding；这里只搬运 id，
 * 名字仍只在所属表里各查一次。
 */
@Component
public class BoardingKeywordResolver {

    /** 关键词长度上限：与客户列表/会话列表同族口径（姓名≤50、号≤11，50 足够容纳片段）。 */
    static final int MAX_KEYWORD_LEN = 50;
    /** 单批行数：只约束一次 SQL / 一次往返的大小，不约束结果集总量。 */
    static final int RESOLVE_BATCH_SIZE = 500;

    private final PetQueryApi petQueryApi;
    private final UserAccountApi userAccountApi;
    private final UserPetApi userPetApi;

    public BoardingKeywordResolver(PetQueryApi petQueryApi, UserAccountApi userAccountApi, UserPetApi userPetApi) {
        this.petQueryApi = petQueryApi;
        this.userAccountApi = userAccountApi;
        this.userPetApi = userPetApi;
    }

    public Resolved resolve(String rawKeyword) {
        String kw = rawKeyword == null ? "" : rawKeyword.trim();
        if (kw.isEmpty()) {
            return Resolved.builder().keyword(false).petIds(List.of()).build();
        }
        if (kw.length() > MAX_KEYWORD_LEN) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID,
                    "搜索关键字过长（≤" + MAX_KEYWORD_LEN + " 字）");
        }
        // TreeSet：既去重（宠物名命中与客户命中常重叠）又升序 —— 分片切法因此是确定的
        Set<Long> petIds = new TreeSet<>(
                keyset(afterId -> petQueryApi.findPetIdsByName(kw, afterId, RESOLVE_BATCH_SIZE)));
        petIds.addAll(ownedPetIds(kw));
        return Resolved.builder()
                .keyword(true)
                .numeric(asPositiveLong(kw))
                .petIds(new ArrayList<>(petIds))
                .build();
    }

    /**
     * 客户昵称/手机号命中的用户 → 其**主登记人**宠物。
     * 只取 OWNER：托管列表「客户」列显示的就是主登记人（{@link BoardingCustomerAssembler}），
     * 若把 FAMILY 共管也算进来，会搜出「客户列根本不是这个人」的行，搜索结果与界面自相矛盾。
     */
    private Set<Long> ownedPetIds(String kw) {
        List<Long> userIds = keyset(afterId ->
                userAccountApi.findUserIdsByNicknameOrPhone(kw, afterId, RESOLVE_BATCH_SIZE));
        Set<Long> pets = new HashSet<>();
        for (List<Long> userChunk : partition(userIds, RESOLVE_BATCH_SIZE)) {
            Set<Long> candidates = new TreeSet<>();
            userPetApi.petIdsByOwners(userChunk).values().forEach(candidates::addAll);
            Set<Long> chunkOwners = new HashSet<>(userChunk);
            for (List<Long> petChunk : partition(new ArrayList<>(candidates), RESOLVE_BATCH_SIZE)) {
                Map<Long, Long> primaryByPet = userPetApi.primaryOwnerIds(petChunk);
                primaryByPet.forEach((petId, ownerId) -> {
                    if (chunkOwners.contains(ownerId)) {
                        pets.add(petId);
                    }
                });
            }
        }
        return pets;
    }

    /**
     * keyset 遍历到「这一批不满一批」为止 ⇒ 拿到全部命中。
     *
     * <p>游标必须严格前进：某批返回的末位 id 不比上一批大，说明实现漏了 {@code id > afterId}。
     * 这里**直接抛错**而不是收口返回——收口就等于交出一份静默截断的结果，
     * 正是本轮要消灭的那个 bug（"搜不到但界面不说"）。死循环由抛错打断，正确性由调用方看到 10009 保证。
     */
    private static List<Long> keyset(Function<Long, List<Long>> batchQuery) {
        List<Long> all = new ArrayList<>();
        Long afterId = null;
        for (;;) {
            List<Long> batch = batchQuery.apply(afterId);
            if (batch == null || batch.isEmpty()) {
                return all;
            }
            all.addAll(batch);
            Long last = batch.get(batch.size() - 1);
            if (batch.size() < RESOLVE_BATCH_SIZE) {
                return all;
            }
            if (last == null || (afterId != null && last <= afterId)) {
                throw new BusinessException(CommonErrorCode.SYSTEM_ERROR,
                        "关键词检索游标未前进，已中止以避免返回不完整的搜索结果");
            }
            afterId = last;
        }
    }

    private static <T> List<List<T>> partition(List<T> ids, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += size) {
            out.add(ids.subList(from, Math.min(from + size, ids.size())));
        }
        return out;
    }

    /** 纯数字关键词同时按「住单 id」和「预约 id」两个维度试；溢出/非纯数字返回 null。 */
    private static Long asPositiveLong(String kw) {
        if (kw.isEmpty() || !kw.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        try {
            long v = Long.parseLong(kw);
            return v > 0 ? v : null;
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    /** 解析结果：{@code petIds} 是**完整**命中集（升序去重）。{@code keyword=false} 表示压根没有关键词。 */
    @Data
    @Builder
    public static class Resolved {
        private boolean keyword;
        private Long numeric;
        private List<Long> petIds;
    }
}
