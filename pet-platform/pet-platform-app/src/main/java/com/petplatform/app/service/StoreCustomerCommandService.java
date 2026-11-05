package com.petplatform.app.service;

import com.petplatform.appointment.api.AppointmentCommandApi;
import com.petplatform.appointment.api.dto.AppointmentCreateRequest;
import com.petplatform.appointment.api.dto.AppointmentCreatedView;
import com.petplatform.pet.api.PetCommandApi;
import com.petplatform.pet.api.dto.PetCreateRequest;
import com.petplatform.pet.api.dto.PetVO;
import com.petplatform.user.api.UserAccountApi;
import org.springframework.stereotype.Service;

/**
 * B 端客户写侧（ADR-057 代客建档 + ADR-058 改档 / 代建宠物 + 代客建单）。
 *
 * <p>为什么本类几乎只是一层转发：手机号唯一性、无密码账号、档案行（含 origin_store_id）都归 user 模块
 * （uk_phone 是唯一最终裁判），宠物建档归 pet 模块（复用 C 端同一条 create 路径），预约创建归
 * appointment 模块（createForCustomer 与 C 端同链）；app 层只负责门店上下文、归属校验（10004 防枚举）
 * 与出参组装——与 StoreCustomerQueryService 同样的 Owner 边界纪律（app 层零直查他模块表）。
 *
 * <p>建档后**不发 UserRegisteredEvent**：那不是注册语义（顾客没来注册），且当前通知链没有该事件的
 * 消费需求；将来若要发欢迎短信，应由推送通道立项时一并裁决。代建宠物同理：PetCreatedEvent 维持
 * 既有发布（全仓无消费者），不新增通知。代客建单**会发** AppointmentCreatedEvent（与 C 端同链）——
 * 顾客 C 端站内信"预约成功"与执行人"来活了"通知均自动生效。
 */
@Service
public class StoreCustomerCommandService {

    private final UserAccountApi userAccountApi;
    private final PetCommandApi petCommandApi;
    private final StoreCustomerQueryService customerQueryService;
    private final AppointmentCommandApi appointmentCommandApi;

    public StoreCustomerCommandService(UserAccountApi userAccountApi, PetCommandApi petCommandApi,
                                       StoreCustomerQueryService customerQueryService,
                                       AppointmentCommandApi appointmentCommandApi) {
        this.userAccountApi = userAccountApi;
        this.petCommandApi = petCommandApi;
        this.customerQueryService = customerQueryService;
        this.appointmentCommandApi = appointmentCommandApi;
    }

    /**
     * 代客建档并返回可直接插入列表的"新行"：零到店 / 无头像 / 无消费 / 无有效预约，
     * 全部字段如实呈现（不编造聚合值），前端拿到即可定位到刚建的客户。
     */
    public StoreCustomerQueryService.CustomerRow create(Long storeId, String phone, String nickname) {
        String p = phone.trim();
        String n = nickname.trim();
        Long userId = userAccountApi.createStoreCustomer(p, n, storeId);
        return StoreCustomerQueryService.CustomerRow.builder()
                .userId(userId)
                .nickname(n)
                .avatarUrl(null)
                .phoneMasked(StoreCustomerQueryService.mask(p))
                .status("NORMAL")
                .firstVisitDate(null)
                .lastVisitDate(null)
                .visitCount(0)
                .totalPaidAmount(null)
                .inStoreStayCount(0)
                .hasValidAppointment(false)
                .build();
    }

    /**
     * 门店侧编辑客户资料（ADR-058）：先过"本店客户集"闸（不在集内 → 10004 防枚举），再定点更新三列
     * （nickname / default_contact_phone / remark）——其余列不在本口射程内。
     */
    public void update(Long storeId, Long userId, String nickname, String contactPhone, String remark) {
        customerQueryService.requireCustomer(storeId, userId);
        userAccountApi.updateStoreCustomerProfile(userId, nickname, contactPhone, remark);
    }

    /**
     * 门店代客建宠物（ADR-058）：同样先过"本店客户集"闸；建档走 pet 域同一条 create 路径
     * （owner=该客户），并把本店记为 origin_store_id——该宠物的"本店可见范围"第三来源（V9）。
     */
    public PetVO createPet(Long storeId, Long userId, PetCreateRequest request) {
        customerQueryService.requireCustomer(storeId, userId);
        return petCommandApi.createForOwner(userId, storeId, request);
    }

    /**
     * 代客建单（新增预约）：先过"本店客户集"闸（10004 防枚举），再走预约域同一条创建链——
     * 下单人=该顾客、source=RECEPTIONIST 可追溯、"服务属本店"校验在预约层内。**不代付**：单据落
     * PENDING_PAYMENT，顾客在 C 端「订单」页支付；同一张表 user_id=顾客 ⇒ C 端"我的预约"天然可见。
     */
    public AppointmentCreatedView createAppointment(Long storeId, Long userId, AppointmentCreateRequest request) {
        customerQueryService.requireCustomer(storeId, userId);
        return appointmentCommandApi.createForCustomer(storeId, userId, request);
    }
}
