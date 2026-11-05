package com.petplatform.app.listener;

import com.petplatform.employee.api.EmployeePermissionApi;
import com.petplatform.employee.api.StoreEmployeeQueryApi;
import com.petplatform.employee.api.dto.EmployeeRosterView;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 门店消息的收件人解析（ADR-047）：一条门店提醒该发给<b>哪些人</b>，判据是权限码，不是角色名。
 *
 * <p><b>为什么不写 {@code roleCodes.contains("STORE_MANAGER")}</b>：能处理某件事的人是谁，唯一真相源是
 * {@code emp_role_permission}。写死角色名之后，任何一次合法授权都不会反映到收件人上——V4 给全体员工补
 * {@code order:refund:view}、V5 给 ADMIN 补 17 个门店执行码都是现成的例子。症状是"权限改了、消息还是不发"，
 * 与权限故障长得一样，最难误诊（ADR-042 已为"照抄文档里的权限码/角色名"付过一次学费）。
 *
 * <p><b>两段收件人缺一不可</b>：
 * <ol>
 *   <li>本店花名册 ∩ 持该码的角色——"权限来自该店归属"这条地基（ADR-008）；</li>
 *   <li>平台管理员（ADMIN）——ADR-044 让他可代入<b>任意 OPEN 门店</b>执行这些动作，
 *       他在 9111 既批得了退款、就应当看得到 9111 的提醒，否则"能批却不知道该批"比没权限更难解释。
 *       注意 ADMIN <b>不在任何真实门店的花名册里</b>（实测其归属只有总部虚拟店 store_id=1），
 *       所以这一段是必需的、不是 {@code storePermissions} 的替代品。</li>
 * </ol>
 *
 * <p><b>这里刻意不调 {@code storePermissions(userId, storeId)}</b>：它对 ADMIN 返回空集
 * （ADR-044 的平台管理员例外只长在 {@code PermissionAssembler} 的分支里），拿它算收件人会再次静默漏掉 ADMIN。
 *
 * <p>ADMIN 段不再校验门店是否 OPEN：进不去那家店的上下文本来就读不到这条消息（权限装配给空码集 → 10003），
 * 消息落库与门店可见性是两件事，不值得为收件人再查一次门店状态。
 *
 * <p>本类只决定"谁收到一条告知"，<b>不授予任何权限</b>；鉴权链一律照旧走 PermissionAssembler。
 */
@Component
public class StoreMessageRecipients {

    /** 唯一可跨门店代入的角色（与 PermissionAssembler 同判据，ADR-044）。 */
    static final String PLATFORM_ADMIN_ROLE = "ADMIN";
    private static final String AFFILIATION_ACTIVE = "ACTIVE";

    private final StoreEmployeeQueryApi storeEmployeeQueryApi;
    private final EmployeePermissionApi employeePermissionApi;

    public StoreMessageRecipients(StoreEmployeeQueryApi storeEmployeeQueryApi,
                                  EmployeePermissionApi employeePermissionApi) {
        this.storeEmployeeQueryApi = storeEmployeeQueryApi;
        this.employeePermissionApi = employeePermissionApi;
    }

    /**
     * @param permissionCode 处理这件事需要的权限码，如 {@code order:refund:approve}
     * @return 去重后的收件人 userId；门店或码为空、无人持有时返回<b>空表</b>（调用方据此只发客户、不视为失败）
     */
    public List<Long> userIdsHolding(Long storeId, String permissionCode) {
        if (storeId == null || storeId <= 0L || permissionCode == null || permissionCode.isBlank()) {
            return List.of();
        }
        Set<String> rolesHolding = employeePermissionApi.rolesHolding(permissionCode);
        if (rolesHolding.isEmpty()) {
            return List.of();
        }
        // LinkedHashSet：多店多角色员工可能出多行，去重且顺序稳定（同一批收件人的写入次序可复现）
        Set<Long> userIds = new LinkedHashSet<>();
        for (EmployeeRosterView row : storeEmployeeQueryApi.rosterInStore(storeId)) {
            if (row == null || !AFFILIATION_ACTIVE.equals(row.getStatus()) || row.getUserId() == null) {
                continue;
            }
            if (row.getRoleCodes() != null && row.getRoleCodes().stream().anyMatch(rolesHolding::contains)) {
                userIds.add(row.getUserId());
            }
        }
        if (rolesHolding.contains(PLATFORM_ADMIN_ROLE)) {
            userIds.addAll(employeePermissionApi.userIdsWithRole(PLATFORM_ADMIN_ROLE));
        }
        return List.copyOf(userIds);
    }
}
