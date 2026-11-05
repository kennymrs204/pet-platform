package com.petplatform.appointment.service;

import com.petplatform.appointment.api.AppointmentErrorCode;
import com.petplatform.appointment.api.dto.AppointmentCancelView;
import com.petplatform.appointment.domain.AppointmentEntity;
import com.petplatform.appointment.mapper.AppointmentCommandMapper;
import com.petplatform.catalog.api.CatalogQueryApi;
import com.petplatform.catalog.api.dto.StoreServiceView;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.order.api.OrderCommandApi;
import com.petplatform.schedule.api.ResourceOccupancyApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 用户取消事务段（ADR-023 §2，取消申请语义按 ADR-043 §1-§2 纠正）：
 * apt 快照分支 → 条件更新（0 行=并发漂移 50003 整体回滚）→ 订单分支。
 *
 * <p><b>两条路径的落点不同，这是本次纠正的核心</b>：
 * <ul>
 *   <li>未支付（PENDING_PAYMENT）：没有钱，当场关单——CANCELLED + 释放占用（旧行为不变）。</li>
 *   <li>已支付（CONFIRMED）：<b>只是提交申请</b>。预约置 CANCEL_REQUESTED、订单进 REFUNDING、
 *       建 Refund(APPLYING)；<b>不释放占用、不发 AppointmentCancelledEvent（住单不撤销）、
 *       不写 CANCELLED</b>。这三件事由门店审批通过且 Mock 退款成功后，经
 *       {@code RefundDecidedEvent} 由装配根回调落定（ADR-043 §4）。</li>
 * </ul>
 * 占用为什么要占到出决定为止：若申请时就释放，店长拒绝后预约恢复成可服务状态却已经没有台位/笼位，
 * 而槽位可能早被别人订走——恢复就是假的。
 *
 * <p>政策按创建时快照读（S7/ADR-060）：新单用 apt 两快照列——**取消时不再读当前上架值**；
 * 仅历史行（快照缺任一，即 V14 之前的单）整体 fallback 实时政策（不可回填、不伪造历史值）。
 * 补齐了 ADR-023 §5 / 第五阶段 :138 C4 当年"加列=迁移，本期不做"记录的口径缺口。
 */
@Slf4j
@Service
public class AppointmentCancelTxService {

    private static final Set<String> IN_PROGRESS = Set.of("SERVING", "WAIT_SERVICE");

    private final AppointmentCommandMapper appointmentMapper;
    private final ResourceOccupancyApi occupancyApi;
    private final OrderCommandApi orderCommandApi;
    private final CatalogQueryApi catalogApi;

    public AppointmentCancelTxService(AppointmentCommandMapper appointmentMapper,
                                      ResourceOccupancyApi occupancyApi, OrderCommandApi orderCommandApi,
                                      CatalogQueryApi catalogApi) {
        this.appointmentMapper = appointmentMapper;
        this.occupancyApi = occupancyApi;
        this.orderCommandApi = orderCommandApi;
        this.catalogApi = catalogApi;
    }

    @Transactional
    public AppointmentCancelView doCancel(AppointmentEntity apt, String reason) {
        String from = apt.getStatus();
        if (IN_PROGRESS.contains(from)) {
            throw new BusinessException(AppointmentErrorCode.SERVICE_IN_PROGRESS);
        }
        if ("CANCEL_REQUESTED".equals(from)) {
            // 重复提交：申请已在审核中。给专用文案，避免前端把 50003 显示成"非法状态流转"
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION,
                    "取消申请已在审核中，请等待门店处理");
        }
        if (!"PENDING_PAYMENT".equals(from) && !"CONFIRMED".equals(from)) {
            throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION);
        }
        boolean paid = "CONFIRMED".equals(from);
        long refundAmount = paid ? calcRefundAmount(apt) : 0L;

        if (paid) {
            if (appointmentMapper.updateCancelRequest(apt.getId(), reason) == 0) {
                throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION); // 快照后并发漂移
            }
            orderCommandApi.refundOnCancel(apt.getOrderId(), refundAmount, reason);
        } else {
            if (appointmentMapper.updateCancelByUser(apt.getId(), from, reason) == 0) {
                throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION); // 快照后并发漂移
            }
            occupancyApi.releaseAll(apt.getId()); // 超时链同款物理删行（第五阶段 §释放）
            if (!orderCommandApi.cancelUnpaid(apt.getOrderId())) {
                throw new BusinessException(AppointmentErrorCode.ILLEGAL_STATE_TRANSITION); // 订单侧漂移
            }
        }
        return AppointmentCancelView.builder()
                .appointmentId(apt.getId()).appointmentNo(apt.getAppointmentNo())
                .status(paid ? "CANCEL_REQUESTED" : "CANCELLED")
                .refundState(paid ? "APPLYING" : "NONE")
                .refundAmount(refundAmount)
                .build();
    }

    /**
     * 退款额（公式与 ADR-023 §5 不变）：距开始 ≥ 免取消小时 → 全额；否则扣 penalty_rate%。
     * S7：政策来源=创建时快照（两列均非空）——取消时零解释（默认/钳制已在写入时完成）；
     * 历史行（任一为 NULL）**整体** fallback 当前上架值——绝不半快照半实时混合计算。
     */
    private long calcRefundAmount(AppointmentEntity apt) {
        long paid = apt.getPriceSnapshot() == null ? 0L : apt.getPriceSnapshot();
        Integer freeHoursSnap = apt.getCancelFreeHoursSnapshot();
        Integer rateSnap = apt.getCancelPenaltyRateSnapshot();
        int freeHours;
        int rate;
        if (freeHoursSnap != null && rateSnap != null) {
            freeHours = freeHoursSnap;
            rate = rateSnap;
        } else {
            StoreServiceView svc = catalogApi.storeServiceOf(apt.getStoreServiceId());
            freeHours = CancelPolicyDefaults.freeHours(svc);
            rate = CancelPolicyDefaults.penaltyRate(svc);
        }
        LocalDateTime start = apt.getBoardingCheckinDate() != null
                ? apt.getBoardingCheckinDate().atStartOfDay()
                : (apt.getServiceDate() == null || apt.getStartTime() == null
                        ? LocalDateTime.now().minusYears(1)
                        : LocalDateTime.of(apt.getServiceDate(), apt.getStartTime()));
        long hoursUntil = Duration.between(LocalDateTime.now(), start).toHours();
        if (hoursUntil >= freeHours) {
            return paid;
        }
        return paid - Math.floorDiv(paid * rate, 100L);
    }
}
