package com.petplatform.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.order.domain.OrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderMapper extends BaseMapper<OrderEntity> {

    /** 支付成功推进（条件更新先到先得，ADR-006-4）。payment_method 由发布方按渠道归一（ONLINE/OFFLINE）。 */
    @Update("UPDATE ord_order SET status = 'PAID', paid_amount = #{paidAmount}, paid_at = NOW(3),"
            + " payment_method = #{paymentMethod}, version = version + 1"
            + " WHERE id = #{orderId} AND deleted = 0 AND status = 'WAIT_PAY'")
    int updateMarkPaid(@Param("orderId") Long orderId, @Param("paidAmount") Long paidAmount,
                       @Param("paymentMethod") String paymentMethod);

    /** 超时取消（条件更新）。 */
    @Update("UPDATE ord_order SET status = 'CANCELLED', version = version + 1"
            + " WHERE id = #{orderId} AND deleted = 0 AND status = 'WAIT_PAY'")
    int updateCancelTimeout(@Param("orderId") Long orderId);

    /** 已付取消进入退款（ADR-023）：PAID→REFUNDING 条件更新。 */
    @Update("UPDATE ord_order SET status = 'REFUNDING', version = version + 1"
            + " WHERE id = #{orderId} AND deleted = 0 AND status = 'PAID'")
    int updateRefundOnCancel(@Param("orderId") Long orderId);

    /** 退款审批通过（ADR-037 §4）：REFUNDING→REFUNDED 条件更新，0 行=状态漂移整笔回滚。 */
    @Update("UPDATE ord_order SET status = 'REFUNDED', version = version + 1"
            + " WHERE id = #{orderId} AND deleted = 0 AND status = 'REFUNDING'")
    int updateRefunded(@Param("orderId") Long orderId);

    /**
     * 退款审批拒绝（ADR-037 §4）：REFUNDING→PAID，即第六阶段 §7 的"回原状态"。
     * 注意预约保持 CANCELLED 不回滚——取消是不可逆动作，拒绝退款只意味着"这笔钱按政策留下"。
     */
    @Update("UPDATE ord_order SET status = 'PAID', version = version + 1"
            + " WHERE id = #{orderId} AND deleted = 0 AND status = 'REFUNDING'")
    int updateRefundRejected(@Param("orderId") Long orderId);

    @Select("SELECT COUNT(*) FROM ord_order WHERE deleted = 0 AND user_id = #{userId} AND status = 'WAIT_PAY'")
    long countUnpaid(@Param("userId") Long userId);
}
