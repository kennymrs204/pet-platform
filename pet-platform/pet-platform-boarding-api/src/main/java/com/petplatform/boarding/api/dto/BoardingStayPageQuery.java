package com.petplatform.boarding.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Collection;

/**
 * 托管主列表的服务端查询条件（分页 + 关键词 + 读侧范围）。
 *
 * <p>刻意做成**一个对象**而不是散参数：列表、count、状态分组三条 SQL 必须共用同一份过滤条件，
 * 各自传一份参数迟早会漂（历史分页就出现过 total 与页码对不上）。这里只给一个实例，三个查询都从它取。
 *
 * <p>关键词由**名字所属模块**解析成 id 后传进来（宠物名在 pet、昵称/手机号在 usr），
 * boarding 只按 id 过滤：门店范围、状态集合、护理员范围仍由本对象携带的条件决定，
 * 关键词命中不能绕过它们（跨店住单不会因为名字恰好相同而浮现）。
 *
 * <p><b>候选 id 是完整的**（keyset 遍历到取完为止），本对象里的 {@code keywordPetIds}
 * 只有在被 {@code BoardingQueryService} 切成一片时才可能小于完整集合；切片之间用
 * {@link #CLAUSE_PET_EXCLUDING_NUMERIC} 互斥，保证「一条住单只落进一个片」，
 * 于是分片求和 = 完整匹配数、分片归并 = 完整有序结果。
 */
@Data
@Builder
public class BoardingStayPageQuery {

    /** 完整关键词条件：数值命中与宠物片之间用 OR 并列（候选未超片大小时走这一条，等价于旧单条 SQL）。 */
    public static final int CLAUSE_FAST = 0;
    /** 只按数值（住单 id / 预约 id）命中。 */
    public static final int CLAUSE_NUMERIC_ONLY = 1;
    /** 只按本片宠物命中，并排除已被数值片取走的行 → 片间互斥，不重复计数。 */
    public static final int CLAUSE_PET_EXCLUDING_NUMERIC = 2;
    /** 只按本片宠物命中（没有数值维度可排除时）。 */
    public static final int CLAUSE_PET_ONLY = 3;

    private Long storeId;
    /** 本业务视图允许的状态集合（页签语义） */
    private Collection<String> statuses;
    /** null=全店；-1=无可见单（调用方应短路，不下 SQL）；其余=该护理员主责（ADR-015 §3） */
    private Long caretakerId;

    /** true=用户输入了关键词；false=完全不做关键词过滤（保持原有无关键词语义） */
    private boolean keyword;
    /** 关键词是纯数字时：它既可能是住单 id，也可能是预约 id，两个维度一起试 */
    private Long keywordNumeric;
    /** 宠物名命中 ∪ 客户（昵称/手机号）命中所属宠物；分片时为本片 */
    private Collection<Long> keywordPetIds;
    /** WHERE 形状（见 CLAUSE_* 常量），由 {@code BoardingQueryService} 决定 */
    @Builder.Default
    private int keywordClause = CLAUSE_FAST;

    private int pageNum;
    private int pageSize;

    /**
     * 有关键词但三个维度都解析不出任何 id → 结果集必为空。
     * 这条判断存在的意义是：不能因为"没得过滤"就悄悄退化成"不过滤"，那会把全店住单当成搜索结果倒出来。
     */
    public boolean isKeywordImpossible() {
        return keyword && keywordNumeric == null
                && (keywordPetIds == null || keywordPetIds.isEmpty());
    }
}
