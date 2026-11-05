package com.petplatform.app.service;

import com.petplatform.appointment.api.AppointmentQueryApi;
import com.petplatform.appointment.api.dto.CustomerAggView;
import com.petplatform.appointment.api.dto.CustomerVisitView;
import com.petplatform.boarding.api.BoardingQueryApi;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.storage.MediaStorage;
import com.petplatform.common.time.BusinessDates;
import com.petplatform.employee.api.EmployeeRosterApi;
import com.petplatform.order.api.OrderStatsApi;
import com.petplatform.pet.api.PetQueryApi;
import com.petplatform.pet.api.dto.PetSummary;
import com.petplatform.store.api.StoreQueryApi;
import com.petplatform.user.api.UserAccountApi;
import com.petplatform.user.api.UserPetApi;
import com.petplatform.user.api.dto.UserProfileView;
import lombok.Builder;
import lombok.Data;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B 端客户视图聚合（ADR-017）。放 app 装配层的原因：数据分属 appointment/user/pet/boarding/order
 * 五个 Owner，app 是唯一可同时依赖多方 api 的组合根（StoreStatsController/PermissionAssembler 先例），
 * 本类零 SQL、零直查他模块表。
 *
 * 口径（ADR-017 §1/§2/§3 + ADR-057 修订）：
 * - 本店客户 = distinct(apt_appointment.user_id WHERE store_id=本店 ∧ deleted=0)
 *   ∪ 本店建档的客户（usr_customer_profile.origin_store_id=本店，ADR-057）− 全部员工 user_id；
 *   不并入 preferred_store_id（那是顾客自选偏好，不是门店归属），不并入 ord_order（与预约 1:1，增量为 0）；
 * - 手机号只在服务端内部出现，出参一律 `phoneMasked`（前 3 后 4）；
 * - `totalPaidAmount` 受 order:view:store 门控，不持有则 null（前端显式"—"），其余字段不披露与否由
 *   customer:view:store / pet:view 决定；
 * - `avatarUrl` 一律是"带签名参数的 presigned GET 地址"（ADR-029 §契约4，600s），**不再有字段的值是裸 key**；
 *   能否签由"桶主必须是这一行的主体"决定（见 signCustomerAvatar / signPetAvatar），存储未开启时全部 null；
 * - 排序 lastVisitDate desc → userId asc，保证翻页稳定。
 *
 * 分页在内存做：跨五个 Owner 的聚合无法下推到单条 SQL（表 Owner 边界优先于分页下推），
 * 本项目 DAU≈300、单店客户量级为百，聚合集小；若未来量级上来，正确解法是落宽表/搜索索引而非改口径。
 */
@Service
public class StoreCustomerQueryService {

    /** 客户列表页签 */
    public static final String TAB_ALL = "ALL";
    public static final String TAB_ACTIVE = "ACTIVE";
    public static final String TAB_DORMANT = "DORMANT";
    public static final String TAB_IN_BOARDING = "IN_BOARDING";

    /** 活跃判定窗口：近 30 天内到店 */
    private static final int ACTIVE_WINDOW_DAYS = 30;
    private static final int DETAIL_VISITS_LIMIT = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final AppointmentQueryApi appointmentApi;
    private final EmployeeRosterApi employeeRosterApi;
    private final UserAccountApi userAccountApi;
    private final UserPetApi userPetApi;
    private final PetQueryApi petApi;
    private final BoardingQueryApi boardingApi;
    private final OrderStatsApi orderStatsApi;
    private final StoreQueryApi storeApi;
    private final ObjectProvider<MediaStorage> media;

    public StoreCustomerQueryService(AppointmentQueryApi appointmentApi, EmployeeRosterApi employeeRosterApi,
                                     UserAccountApi userAccountApi, UserPetApi userPetApi, PetQueryApi petApi,
                                     BoardingQueryApi boardingApi, OrderStatsApi orderStatsApi,
                                     StoreQueryApi storeApi, ObjectProvider<MediaStorage> media) {
        this.appointmentApi = appointmentApi;
        this.employeeRosterApi = employeeRosterApi;
        this.userAccountApi = userAccountApi;
        this.userPetApi = userPetApi;
        this.petApi = petApi;
        this.boardingApi = boardingApi;
        this.orderStatsApi = orderStatsApi;
        this.storeApi = storeApi;
        this.media = media;
    }

    /**
     * 客户集（已减员工账号）：**本店预约 ∪ 本店建档**（ADR-057 扩展 ADR-017 §1 口径），
     * 按 lastVisitDate desc → userId asc 排序（无到店记录的建档客户排最后，nullsLast）。
     */
    public List<CustomerAggView> customersOf(Long storeId) {
        Set<Long> staffUserIds = employeeRosterApi.employeeUserIds();
        List<CustomerAggView> aggs = appointmentApi.customerAggInStore(storeId).stream()
                .filter(a -> a.getUserId() != null && !staffUserIds.contains(a.getUserId()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        // ADR-057 第二来源：本店建档但从未在本店预约的客户 —— 补"零到店"合成聚合行
        Set<Long> seen = aggs.stream().map(CustomerAggView::getUserId).collect(java.util.stream.Collectors.toSet());
        userAccountApi.customerUserIdsByOriginStore(storeId).stream()
                .filter(uid -> uid != null && !seen.contains(uid) && !staffUserIds.contains(uid))
                .forEach(uid -> aggs.add(CustomerAggView.builder()
                        .userId(uid)
                        .visitCount(0)
                        .hasValidAppointment(false)
                        .build()));
        return aggs.stream()
                .sorted(Comparator.comparing(CustomerAggView::getLastVisitDate,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(CustomerAggView::getUserId))
                .toList();
    }

    public PageResult<CustomerRow> page(Long storeId, String tab, String keyword, int pageNum, int pageSize,
                                        AuthPrincipal principal) {
        String tabNorm = tab == null || tab.isBlank() ? TAB_ALL : tab.trim().toUpperCase();
        if (!List.of(TAB_ALL, TAB_ACTIVE, TAB_DORMANT, TAB_IN_BOARDING).contains(tabNorm)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "非法客户页签: " + tab);
        }
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);

        List<CustomerAggView> aggs = customersOf(storeId);
        if (aggs.isEmpty()) {
            return PageResult.of(0, page, size, List.of());
        }
        Context ctx = loadContext(storeId, aggs, principal);
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();

        List<CustomerRow> matched = new ArrayList<>();
        for (CustomerAggView agg : aggs) {
            CustomerRow row = toRow(agg, ctx, principal);
            if (!matchesTab(row, tabNorm) || !matchesKeyword(row, ctx, kw)) {
                continue;
            }
            matched.add(row);
        }
        int from = Math.min((page - 1) * size, matched.size());
        int to = Math.min(from + size, matched.size());
        return PageResult.of(matched.size(), page, size, List.copyOf(matched.subList(from, to)));
    }

    public CustomerStats stats(Long storeId, AuthPrincipal principal) {
        List<CustomerAggView> aggs = customersOf(storeId);
        if (aggs.isEmpty()) {
            return CustomerStats.builder().totalCustomers(0).newLast30Days(0).inBoardingCustomers(0).build();
        }
        Context ctx = loadContext(storeId, aggs, principal);
        LocalDate floor = BusinessDates.today().minusDays(ACTIVE_WINDOW_DAYS - 1L);
        long total = aggs.size();
        long fresh = aggs.stream()
                .filter(a -> a.getFirstVisitDate() != null && !a.getFirstVisitDate().isBefore(floor))
                .count();
        long inBoarding = aggs.stream().filter(a -> inStoreStaysOf(a.getUserId(), ctx) > 0).count();
        return CustomerStats.builder().totalCustomers(total).newLast30Days(fresh).inBoardingCustomers(inBoarding).build();
    }

    /** 详情；userId 不在本店客户集 → 10004（与跨店看单同口径，不区分"不存在"以防枚举探测）。 */
    public CustomerDetail detail(Long storeId, Long userId, AuthPrincipal principal) {
        CustomerAggView agg = customersOf(storeId).stream()
                .filter(a -> a.getUserId().equals(userId)).findFirst()
                .orElseThrow(() -> BusinessException.of(CommonErrorCode.NOT_FOUND));
        Context ctx = loadContext(storeId, List.of(agg), principal);
        CustomerRow row = toRow(agg, ctx, principal);
        UserProfileView profile = ctx.profiles.get(userId);
        List<CustomerPetRow> pets = petsOf(storeId, userId, principal);
        List<CustomerVisitRow> visits = visitsOf(storeId, userId, DETAIL_VISITS_LIMIT, 0, principal);
        return CustomerDetail.builder()
                .userId(row.userId).nickname(row.nickname).avatarUrl(row.avatarUrl).phoneMasked(row.phoneMasked)
                .status(row.status).firstVisitDate(row.firstVisitDate).lastVisitDate(row.lastVisitDate)
                .visitCount(row.visitCount).totalPaidAmount(row.totalPaidAmount)
                .inStoreStayCount(row.inStoreStayCount).hasValidAppointment(row.hasValidAppointment)
                .profile(CustomerProfileRow.builder()
                        .registeredAt(profile == null ? null : profile.getRegisteredAt())
                        .preferredStoreName(storeNameOf(profile == null ? null : profile.getPreferredStoreId()))
                        .marketingOptIn(profile != null && Boolean.TRUE.equals(profile.getMarketingOptIn()))
                        .defaultContactPhone(profile == null ? null : profile.getDefaultContactPhone())
                        .remark(profile == null ? null : profile.getRemark())
                        .build())
                .pets(pets).visits(visits)
                .build();
    }

    /** 客户名下宠物；明细字段（品种/头像）需 pet:view，名字始终给出（否则列表无法辨认）。 */
    public List<CustomerPetRow> petsOf(Long storeId, Long userId, AuthPrincipal principal) {
        requireCustomer(storeId, userId);
        boolean canViewPet = can(principal, "pet:view");
        Map<Long, List<Long>> byOwner = userPetApi.petIdsByOwners(List.of(userId));
        List<Long> petIds = byOwner.getOrDefault(userId, List.of()).stream().sorted().toList();
        if (petIds.isEmpty()) {
            return List.of();
        }
        Map<Long, PetSummary> pets = summaries(petIds);
        return petIds.stream().map(id -> {
            PetSummary p = pets.get(id);
            if (p == null) {
                return null;
            }
            return CustomerPetRow.builder()
                    .petId(p.getId())
                    .name(p.getName())
                    .species(canViewPet ? p.getSpecies() : null)
                    .breed(canViewPet ? p.getBreed() : null)
                    .avatarUrl(canViewPet ? signPetAvatar(p.getAvatar(), id) : null)
                    .build();
        }).filter(java.util.Objects::nonNull).toList();
    }

    /** 到店史（快照直出；员工姓名经 employee-api 回显）。offset 供「每次只查 N 条」的分页下推。 */
    public List<CustomerVisitRow> visitsOf(Long storeId, Long userId, int limit, int offset, AuthPrincipal principal) {
        requireCustomer(storeId, userId);
        List<CustomerVisitView> visits = appointmentApi.visitsOfCustomer(storeId, userId, limit, offset);
        List<Long> employeeIds = visits.stream().map(CustomerVisitView::getEmployeeId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> names = employeeIds.isEmpty() ? Map.of()
                : employeeRosterApi.staffInStore(storeId).stream()
                .collect(HashMap::new, (m, s) -> m.put(s.getEmployeeId(), s.getName()), HashMap::putAll);
        return visits.stream().map(v -> CustomerVisitRow.builder()
                .appointmentId(v.getAppointmentId())
                .serviceDate(v.getServiceDate())
                .serviceName(v.getServiceName())
                .status(v.getStatus())
                .amount(v.getPriceSnapshot())
                .employeeName(v.getEmployeeId() == null ? null : names.get(v.getEmployeeId()))
                .petId(v.getPetId())
                .build()).toList();
    }

    /** 客户集存在性校验（写侧也复用：userId 不在本店客户集 → 10004，防枚举）。 */
    public void requireCustomer(Long storeId, Long userId) {
        boolean in = userId != null && customersOf(storeId).stream().anyMatch(a -> userId.equals(a.getUserId()));
        if (!in) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
    }

    /* ---- 内部装配 ---- */

    private Context loadContext(Long storeId, List<CustomerAggView> aggs, AuthPrincipal principal) {
        List<Long> userIds = aggs.stream().map(CustomerAggView::getUserId).toList();
        Map<Long, UserProfileView> profiles = new HashMap<>();
        userAccountApi.profilesByIds(userIds).forEach(p -> profiles.put(p.getUserId(), p));

        Map<Long, List<Long>> ownerPets = userPetApi.petIdsByOwners(userIds);
        List<Long> allPetIds = ownerPets.values().stream().flatMap(List::stream).distinct().toList();
        Map<Long, PetSummary> pets = summaries(allPetIds);

        Map<Long, Long> stayCountByPet = new HashMap<>();
        boardingApi.currentStays(allPetIds).forEach(s ->
                stayCountByPet.merge(s.getPetId(), 1L, Long::sum));

        Map<Long, Long> paid = can(principal, "order:view:store")
                ? orderStatsApi.paidAmountByUsers(storeId, userIds) : Map.of();

        return new Context(profiles, ownerPets, pets, stayCountByPet, paid);
    }

    private Map<Long, PetSummary> summaries(List<Long> petIds) {
        Map<Long, PetSummary> byId = new HashMap<>();
        if (!petIds.isEmpty()) {
            petApi.summaries(petIds).forEach(p -> byId.put(p.getId(), p));
        }
        return byId;
    }

    private CustomerRow toRow(CustomerAggView agg, Context ctx, AuthPrincipal principal) {
        UserProfileView profile = ctx.profiles.get(agg.getUserId());
        return CustomerRow.builder()
                .userId(agg.getUserId())
                .nickname(profile == null || profile.getNickname() == null || profile.getNickname().isBlank()
                        ? "顾客" + agg.getUserId() : profile.getNickname())
                .avatarUrl(profile == null ? null : signCustomerAvatar(profile.getAvatar(), agg.getUserId()))
                .phoneMasked(profile == null ? "" : mask(profile.getPhone()))
                .status(profile == null ? "NORMAL" : profile.getStatus())
                .firstVisitDate(agg.getFirstVisitDate())
                .lastVisitDate(agg.getLastVisitDate())
                .visitCount(agg.getVisitCount())
                .totalPaidAmount(ctx.paid.get(agg.getUserId()))
                .inStoreStayCount(inStoreStaysOf(agg.getUserId(), ctx))
                .hasValidAppointment(agg.isHasValidAppointment())
                .build();
    }

    private long inStoreStaysOf(Long userId, Context ctx) {
        return ctx.ownerPets.getOrDefault(userId, List.of()).stream()
                .mapToLong(id -> ctx.stayCountByPet.getOrDefault(id, 0L)).sum();
    }

    /* ---- 头像签名（ADR-029 §契约4：闸的主语是"这一行的主体"，与查看者是谁无关） ---- */

    /** 客户头像：桶主必须是该客户本人（与 CustomerProfileService.signAvatar 的 self 口径同规则）。 */
    private String signCustomerAvatar(String key, Long customerUserId) {
        Long owner = MediaStorage.keyOwner(key);
        return owner != null && owner.equals(customerUserId) ? presign(key) : null;
    }

    /** 宠物头像：复用 PetService.signAvatar 的同一个 canManage 判定，B/C 两侧对同一字段不留两套规则。 */
    private String signPetAvatar(String key, Long petId) {
        Long owner = MediaStorage.keyOwner(key);
        return owner != null && userPetApi.canManage(owner, petId) ? presign(key) : null;
    }

    /**
     * presign 是"锦上添花"的展示地址，不是业务数据：MinIO SDK 签名前要探测桶 region（真网络请求），
     * 桶不存在/存储短暂不可达都会抛异常 → 若让它冒泡，整页客户列表会因一个头像 500。
     * 故这里降级为 null（前端回落首字母占位），与 pet.storage.enabled=false 同一条降级路径。
     */
    private String presign(String key) {
        MediaStorage storage = media.getIfAvailable();
        if (storage == null) {
            return null;
        }
        try {
            return storage.presignGet(key, MediaStorage.GET_TTL);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 偏好门店换名；id 为空或门店已删（storeNames 静默跳过）返回 null，前端按"未选择"展示。 */
    private String storeNameOf(Long storeId) {
        if (storeId == null) {
            return null;
        }
        return storeApi.storeNames(List.of(storeId)).get(storeId);
    }

    private static boolean matchesTab(CustomerRow row, String tab) {
        if (TAB_ALL.equals(tab)) {
            return true;
        }
        if (TAB_IN_BOARDING.equals(tab)) {
            return row.inStoreStayCount > 0;
        }
        Long days = daysSince(row.lastVisitDate);
        boolean recent = days != null && days <= ACTIVE_WINDOW_DAYS;
        return TAB_ACTIVE.equals(tab)
                ? (recent || row.hasValidAppointment)
                : (days != null && days > ACTIVE_WINDOW_DAYS && !row.hasValidAppointment);
    }

    /** keyword 命中昵称 / 手机号（完整号或脱敏号前缀）/ 客户名下宠物名。 */
    private static boolean matchesKeyword(CustomerRow row, Context ctx, String kw) {
        if (kw.isEmpty()) {
            return true;
        }
        if (row.nickname != null && row.nickname.toLowerCase().contains(kw)) {
            return true;
        }
        UserProfileView profile = ctx.profiles.get(row.userId);
        if (profile != null && profile.getPhone() != null && profile.getPhone().toLowerCase().contains(kw)) {
            return true;
        }
        if (row.phoneMasked != null && row.phoneMasked.toLowerCase().contains(kw)) {
            return true;
        }
        return ctx.ownerPets.getOrDefault(row.userId, List.of()).stream()
                .map(ctx.pets::get).filter(java.util.Objects::nonNull)
                .anyMatch(p -> p.getName() != null && p.getName().toLowerCase().contains(kw));
    }

    private static Long daysSince(LocalDate date) {
        return date == null ? null : java.time.temporal.ChronoUnit.DAYS.between(date, BusinessDates.today());
    }

    /** 服务端脱敏（ADR-017 §3）：保留前 3 后 4；异常长度一律全遮，不抛错也不漏号。客户沟通等 B 端出口复用同一实现。 */
    public static String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone == null ? "" : "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static boolean can(AuthPrincipal principal, String rawCode) {
        return principal.permissions().contains(rawCode);
    }

    /** 一次列表/详情请求内的共享上下文（避免逐行 N+1 打库）。 */
    private static final class Context {
        private final Map<Long, UserProfileView> profiles;
        private final Map<Long, List<Long>> ownerPets;
        private final Map<Long, PetSummary> pets;
        private final Map<Long, Long> stayCountByPet;
        private final Map<Long, Long> paid;

        private Context(Map<Long, UserProfileView> profiles, Map<Long, List<Long>> ownerPets,
                        Map<Long, PetSummary> pets, Map<Long, Long> stayCountByPet, Map<Long, Long> paid) {
            this.profiles = profiles;
            this.ownerPets = ownerPets;
            this.pets = pets;
            this.stayCountByPet = stayCountByPet;
            this.paid = paid;
        }
    }

    /* ---- 出参视图（Long/long 经全局 Jackson 字符串化，前端按 string 接） ---- */

    @Data
    @Builder
    public static class CustomerRow {
        private Long userId;
        private String nickname;
        private String avatarUrl;
        private String phoneMasked;
        private String status;
        private LocalDate firstVisitDate;
        private LocalDate lastVisitDate;
        private long visitCount;
        /** null = 当前账号无 order:view:store，不披露 */
        private Long totalPaidAmount;
        private long inStoreStayCount;
        private boolean hasValidAppointment;
    }

    @Data
    @Builder
    public static class CustomerProfileRow {
        private java.time.LocalDateTime registeredAt;
        /** store 段 StoreQueryApi 上线前恒为 null（表 Owner 边界，不直查 sto_store） */
        private String preferredStoreName;
        private boolean marketingOptIn;
        /** 默认联系电话（ADR-027/058；手机号或空，门店侧编辑回显用） */
        private String defaultContactPhone;
        /** 门店侧客户备注（ADR-058；C 端不展示） */
        private String remark;
    }

    @Data
    @Builder
    public static class CustomerPetRow {
        private Long petId;
        private String name;
        private String species;
        private String breed;
        private String avatarUrl;
    }

    @Data
    @Builder
    public static class CustomerVisitRow {
        private Long appointmentId;
        private LocalDate serviceDate;
        private String serviceName;
        private String status;
        private Long amount;
        private String employeeName;
        private Long petId;
    }

    @Data
    @Builder
    public static class CustomerDetail {
        private Long userId;
        private String nickname;
        private String avatarUrl;
        private String phoneMasked;
        private String status;
        private LocalDate firstVisitDate;
        private LocalDate lastVisitDate;
        private long visitCount;
        private Long totalPaidAmount;
        private long inStoreStayCount;
        private boolean hasValidAppointment;
        private CustomerProfileRow profile;
        private List<CustomerPetRow> pets;
        private List<CustomerVisitRow> visits;
    }

    @Data
    @Builder
    public static class CustomerStats {
        private long totalCustomers;
        private long newLast30Days;
        private long inBoardingCustomers;
    }
}
