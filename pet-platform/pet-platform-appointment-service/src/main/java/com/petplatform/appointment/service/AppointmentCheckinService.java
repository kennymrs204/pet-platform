package com.petplatform.appointment.service;

import com.petplatform.appointment.api.AppointmentErrorCode;
import com.petplatform.appointment.api.dto.AppointmentCheckinView;
import com.petplatform.appointment.domain.AppointmentEntity;
import com.petplatform.appointment.mapper.AppointmentCommandMapper;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.time.BusinessDates;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 到店签到（ADR-042）：门店侧第一个预约状态动作，语义 = 顾客人已到店，把单从「已确认」推进「待服务」。
 *
 * <p>先例沿用 {@link AppointmentCommandService}：<b>条件更新先到先得</b>（不预占、不加分布式锁），
 * 非法迁移 50003，重放静默。签到是单人单行的前台动作，没有多步副作用，因此不需要
 * Cancel/Create 那种 Service+TxService 拆分（那两层是为"退款单+状态+事件"同事务而存在的）。
 * 单条条件 UPDATE 本身就是原子的，套一层 @Transactional 不增加任何保证。
 *
 * <p>刻意不做：① <b>不写 {@code actual_start_at}</b>——该列语义是"服务实际开始"，属任务链 task:start，
 * 库里没有到店时间列，不借用语义不同的列凑数（页面因此不显示"到店时间"，只显示状态）；
 * ② <b>不动 {@code ord_order.status}</b>——全仓没有任何代码驱动 order→WAIT_SERVICE（订单状态机停在 PAID
 * 是既有缺口，不是本片引入），跨模块顺手改订单会造出第二处状态真相；
 * ③ 不发通知（notification 段未接线）。
 */
@Service
public class AppointmentCheckinService {

    private final AppointmentCommandMapper mapper;

    public AppointmentCheckinService(AppointmentCommandMapper mapper) {
        this.mapper = mapper;
    }

    public AppointmentCheckinView checkin(AuthPrincipal principal, Long appointmentId) {
        Long storeCtx = principal == null ? null : principal.storeCtx();
        if (storeCtx == null || storeCtx == 0L) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "缺少 X-Store-Id 门店上下文");
        }
        if (appointmentId == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "appointmentId 必填");
        }
        AppointmentEntity apt = mapper.selectById(appointmentId);
        // 跨店与不存在同口径 10004（不透露"这单在别的店"，同 ADR-015 对住单的口径）
        if (apt == null || !storeCtx.equals(apt.getStoreId())) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        if (apt.getBoardingCheckinDate() != null) {
            // 托管单到店=办理入住（要分笼位+护理员，并把预约直接推到 SERVING）。若在这里先签成 WAIT_SERVICE，
            // boarding 的 updateStartBoarding(WHERE status='CONFIRMED') 就永远打不动了——两个流程抢同一前提。
            throw new BusinessException(CommonErrorCode.PARAM_INVALID,
                    "跨天托管单请到「托管服务」页办理入住（会同时分配笼位与护理员）");
        }
        LocalDate today = BusinessDates.today();
        if (!today.equals(apt.getServiceDate())) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID,
                    "仅当日预约可签到（该单服务日期 " + apt.getServiceDate() + "，今天 " + today + "）");
        }
        String status = apt.getStatus();
        if ("PENDING_PAYMENT".equals(status)) {
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION, "预约尚未支付，不能签到");
        }
        if ("CANCELLED".equals(status)) {
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION, "预约已取消，不能签到");
        }
        if (!"CONFIRMED".equals(status)) {
            // WAIT_SERVICE / SERVING / COMPLETED：早已越过到店点 → 幂等成功，不报错
            return view(apt, status, false);
        }
        if (mapper.updateCheckin(apt.getId()) == 0) {
            AppointmentEntity now = mapper.selectById(apt.getId());
            String latest = now == null ? null : now.getStatus();
            if ("WAIT_SERVICE".equals(latest)) {
                return view(now, latest, false);   // 并发竞争失败方：对方已推进，同样算成功
            }
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION, "预约状态已变更，请刷新后重试");
        }
        return view(apt, "WAIT_SERVICE", true);
    }

    private static AppointmentCheckinView view(AppointmentEntity apt, String status, boolean changed) {
        return AppointmentCheckinView.builder()
                .appointmentId(apt.getId())
                .appointmentNo(apt.getAppointmentNo())
                .status(status)
                .changed(changed)
                .build();
    }
}
