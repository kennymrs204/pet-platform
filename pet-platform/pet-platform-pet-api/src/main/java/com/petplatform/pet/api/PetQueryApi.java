package com.petplatform.pet.api;

import com.petplatform.pet.api.dto.PetSummary;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 宠物档案只读查询（表 Owner：pet 模块）。首页/列表等跨模块场景经本 api 补宠物摘要，
 * 不得直查 pet_pet（第二阶段 §3.2 依赖矩阵）。
 */
public interface PetQueryApi {

    /** 批量取宠物摘要；不存在的 id 静默跳过；顺序不保证。 */
    List<PetSummary> summaries(Collection<Long> petIds);

    /**
     * 批量取宠物名，<b>含已逻辑删除的档案</b>；不存在或名字为空白的 id 静默跳过。
     *
     * <p>专供<b>历史业务引用</b>回填名字（如托管历史住单）：宠物删除是逻辑删且明确不级联
     * 历史业务（StorePetDeleteService 契约），历史行不能因为档案被删就连名字都读不出。
     * 只回填 name 一项——species/头像等档案属性随删除对历史行一并不可见，是有意口径。
     * 活体业务（新建预约/入住等）禁止用本方法绕过 deleted 过滤。
     */
    Map<Long, String> namesIncludingDeleted(Collection<Long> petIds);

    /**
     * 事务内锁定宠物行并返回当前摘要；已删/不存在返回 null。
     * 新建预约/托管在事务段调用本方法，与宠物删除统一以 pet_pet 行锁串行：
     * 删除先完成 → 新业务拿到 null 并失败；新业务先提交 → 删除在锁后重新检查阻断记录。
     * 必须在已有事务中调用（方法本身不加事务代理）。
     */
    PetSummary lockForUse(Long petId);

    /**
     * 批量取宠物头像的 presigned GET（ADR-029 §契约4，归属闸见 pet 模块 PetAvatarSigner）。
     * 跨模块要出图只走本方法——{@link PetSummary#getAvatar()} 是**裸对象 key**，
     * 直接进 {@code <img src>} 会被浏览器按前端站点解析成 404 裂图。
     *
     * @return petId → 签名地址；存储未开启、key 非法或归属校验不过的宠物不出现在结果里
     */
    Map<Long, String> avatarUrls(Collection<Long> petIds);

    /**
     * 按宠物名**模糊匹配**取 pet_id（服务端关键词搜索用，例：托管主列表按宠物名搜）。
     *
     * <p>名字的唯一真相在 pet 表，所以「名字→id 集合」这一步必须由本模块执行；
     * 调用方（boarding）拿到 id 后仍用自己的 {@code store_id} 条件过滤，跨店记录不会因为
     * 名字命中而浮现。空/空白关键词返回空列表（= 不发起查询），不返回全量。
     *
     * <p><b>本方法是 keyset 游标式，不是"取前 N 条"</b>：{@code afterId} 传上一批的最大 id，
     * 调用方循环到「返回条数 &lt; limit」为止即可拿到**全部**命中，不存在静默截断；
     * {@code limit} 只约束单次往返的行数（SQL 侧 {@code LIMIT}），不是结果集上限。
     *
     * @param afterId 只返回 id 严格大于它的行；首批传 null
     * @param limit   单批最大行数（须 &gt; 0）
     */
    List<Long> findPetIdsByName(String keyword, Long afterId, int limit);
}
