package com.petplatform.user.api;

import com.petplatform.user.api.dto.UserProfileView;

import java.util.Collection;
import java.util.List;

/**
 * 账号查询端口：供 app 的权限装配器判断 C 端身份（第八阶段 §1.2：
 * 有 CustomerProfile 才并入 CUSTOMER 权限码）。
 */
public interface UserAccountApi {

    /** 返回 usr_user.status（NORMAL/DISABLED）；账号不存在返回 null。 */
    String statusOf(Long userId);

    boolean existsCustomerProfile(Long userId);

    /**
     * 批量取客户档案（ADR-017 §6 增量，B 端客户视图数据源）。
     * 不存在的 userId 静默跳过；无 usr_customer_profile 行时 preferredStoreId/marketingOptIn 为 null。
     * **返回完整 phone 仅供服务端脱敏，禁止直发前端**（ADR-017 §3）。
     */
    List<UserProfileView> profilesByIds(Collection<Long> userIds);

    /**
     * 手机号 → 账号 id（ADR-033 员工纳编用：店长录入员工必须先有已注册账号）。
     *
     * <p>刻意只回 id 不回档案：纳编动作需要的是"这个手机号是不是一个已验证的登录主体"，
     * 把 nickname/phone 一起带出去只会无谓扩大个人信息的出口。未注册返回 null（调用方按 10004 引导注册）。
     */
    Long userIdByPhone(String phone);

    /**
     * 昵称或手机号**模糊匹配**取 user_id（B 端按客户搜跨模块用，例：托管主列表按客户名/手机号搜）。
     *
     * <p>只回 id、不回档案：命中即出参昵称/手机号会把「搜索」变成「批量导出客户信息」的旁路。
     * 手机号在这里只用于**匹配**（数据库原始列），页面展示的仍是各调用方自己服务端脱敏后的号码。
     * 空/空白关键词返回空列表（= 不发起查询）。
     *
     * <p><b>keyset 游标式，不是"取前 N 条"</b>：{@code afterId} 传上一批的最大 id，调用方循环到
     * 「返回条数 &lt; limit」为止即可拿到**全部**命中，不存在静默截断；{@code limit} 只约束单批行数。
     *
     * @param afterId 只返回 id 严格大于它的行；首批传 null
     * @param limit   单批最大行数（须 &gt; 0）
     */
    List<Long> findUserIdsByNicknameOrPhone(String keyword, Long afterId, int limit);

    /**
     * 门店代客建档（ADR-057）：新建**无密码**客户账号 + 带建档门店的档案行，返回新 user_id。
     *
     * <p>不设密码是刻意的：顾客后续可用「验证码登录」直接进，或用「忘记密码（RESET 场景码）」设密码认领
     * （ADR-011 的 resetPassword 不要求原密码，已核）。手机号已存在——无论是注册用户还是他店/本店已建档
     * ——一律 {@code 20001}（超集语义：本口只建"新号码"，不做"关联已有账号"）。
     * 唯一性最终裁判是 {@code uk_phone}：并发下 DuplicateKeyException 同样归一成 20001。
     */
    Long createStoreCustomer(String phone, String nickname, Long originStoreId);

    /**
     * 本店建档的客户 user_id（ADR-057 口径的第二来源：本店客户 = 本店预约 ∪ 本店建档，
     * 员工剔除由 app 装配层完成——user 域不认识员工域）。
     */
    List<Long> customerUserIdsByOriginStore(Long storeId);

    /**
     * 门店侧编辑客户资料（ADR-058）：**定点更新三列**——usr_user.nickname +
     * usr_customer_profile.default_contact_phone / remark。
     *
     * <p>刻意不做全量写：avatar/status/phone/preferred_store_id/marketing_opt_in 不在此口射程内
     * （前两者属平台/上传面，后两者语义属顾客本人；全量写少带键会覆盖成 NULL——ADR-020 教训）。
     * 入参在服务层归一：nickname 去空白；contactPhone/remark 空白一律置 null（空=清空）。
     */
    void updateStoreCustomerProfile(Long userId, String nickname, String contactPhone, String remark);
}
