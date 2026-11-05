package com.petplatform.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.order.api.dto.RefundView;
import com.petplatform.order.domain.RefundEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RefundMapper extends BaseMapper<RefundEntity> {

    /** Σ退款≤实付守卫（第六阶段 :125）：被拒单不计。 */
    @Select("SELECT IFNULL(SUM(amount), 0) FROM ord_refund"
            + " WHERE deleted = 0 AND order_id = #{orderId} AND status <> 'REJECTED'")
    long sumActiveRefunds(@Param("orderId") Long orderId);

    /**
     * 退款视图列（ADR-037）：join ord_order 补 store_id/appointment_id/user_id/paid_amount——
     * B 端按店筛、C 端校归属、通知取收件人都靠这四列，缺一个就要再跳一次查询。
     * 列名保持下划线，由 map-underscore-to-camel-case 落到 RefundView。
     */
    String VIEW = "SELECT r.id, r.refund_no, r.order_id, r.amount, r.reason, r.reject_reason, r.status, r.apply_by,"
            + " r.approve_role, r.approver_id, r.created_at, r.refunded_at,"
            + " o.appointment_id, o.store_id, o.user_id, o.paid_amount"
            + " FROM ord_refund r JOIN ord_order o ON o.id = r.order_id AND o.deleted = 0";

    @Select(VIEW + " WHERE r.deleted = 0 AND r.id = #{refundId}")
    RefundView selectViewById(@Param("refundId") Long refundId);

    @Select(VIEW + " WHERE r.deleted = 0 AND r.order_id = #{orderId}"
            + " ORDER BY r.created_at DESC, r.id DESC")
    List<RefundView> selectViewsByOrder(@Param("orderId") Long orderId);

    @Select("<script>"
            + VIEW
            + " WHERE r.deleted = 0"
            + " <if test='storeId != null'> AND o.store_id = #{storeId}</if>"
            + " <if test='status != null and status != \"\"'> AND r.status = #{status}</if>"
            + " ORDER BY r.created_at DESC, r.id DESC"
            + " LIMIT #{pageSize} OFFSET #{offset}"
            + "</script>")
    List<RefundView> selectViewPage(@Param("status") String status, @Param("storeId") Long storeId,
                                    @Param("pageSize") int pageSize, @Param("offset") long offset);

    @Select("<script>"
            + " SELECT COUNT(*) FROM ord_refund r JOIN ord_order o ON o.id = r.order_id AND o.deleted = 0"
            + " WHERE r.deleted = 0"
            + " <if test='storeId != null'> AND o.store_id = #{storeId}</if>"
            + " <if test='status != null and status != \"\"'> AND r.status = #{status}</if>"
            + "</script>")
    long countViewPage(@Param("status") String status, @Param("storeId") Long storeId);

    /**
     * 超时自动退款候选（本单设计）：到点仍未被人工处理的 APPLYING 单，按 id 升序喂给
     * {@code RefundApprovalApi.autoApprove}。上限 200 一批——超了下一跳（60s）自然续。
     */
    @Select("SELECT id FROM ord_refund WHERE deleted = 0 AND status = 'APPLYING'"
            + " AND created_at <= #{cutoff} ORDER BY id ASC LIMIT 200")
    List<Long> selectAutoApprovableIds(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 审批通过（ADR-043 §4 第一步）：APPLYING→APPROVED 条件更新。
     * <b>此刻钱还没动</b>：不写 refunded_at、不改订单、不发"退款成功"——那些都在退款执行成功之后。
     * 状态即 CAS 锁——0 行=已被他人处理或状态漂移，调用方抛 60005。
     */
    @Update("UPDATE ord_refund SET status = 'APPROVED', approve_role = #{approveRole},"
            + " approver_id = #{approverId}, version = version + 1"
            + " WHERE id = #{refundId} AND deleted = 0 AND status = 'APPLYING'")
    int updateApproved(@Param("refundId") Long refundId, @Param("approveRole") String approveRole,
                       @Param("approverId") Long approverId);

    /**
     * 退款执行成功（ADR-043 §4 第二步）：APPROVED→SUCCESS + 落 refunded_at。
     * 只有这条写成功，订单才允许 REFUNDING→REFUNDED、预约才允许落 CANCELLED、
     * 才允许发 REFUND_SUCCESS——这就是"消息不早于钱"的机制保证。
     */
    @Update("UPDATE ord_refund SET status = 'SUCCESS', refunded_at = CURRENT_TIMESTAMP(3),"
            + " version = version + 1"
            + " WHERE id = #{refundId} AND deleted = 0 AND status = 'APPROVED'")
    int updateExecutedSuccess(@Param("refundId") Long refundId);

    /**
     * 审批拒绝（ADR-037 §4）：APPLYING→REJECTED，订单回 PAID 由 OrderMapper 同事务跟进。
     * rejectReason 必填（V17 起落库）：被拒时顾客要在退款进度卡看到原因。
     */
    @Update("UPDATE ord_refund SET status = 'REJECTED', approve_role = #{approveRole},"
            + " approver_id = #{approverId}, reject_reason = #{rejectReason}, version = version + 1"
            + " WHERE id = #{refundId} AND deleted = 0 AND status = 'APPLYING'")
    int updateRejected(@Param("refundId") Long refundId, @Param("approveRole") String approveRole,
                       @Param("approverId") Long approverId, @Param("rejectReason") String rejectReason);
}
