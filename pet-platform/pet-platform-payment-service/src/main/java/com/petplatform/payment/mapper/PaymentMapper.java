package com.petplatform.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.payment.domain.PaymentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PaymentMapper extends BaseMapper<PaymentEntity> {

    @Select("SELECT COUNT(*) FROM pay_payment WHERE deleted = 0 AND channel_txn_no = #{txnNo}")
    long countByChannelTxn(@Param("txnNo") String txnNo);

    @Select("SELECT * FROM pay_payment WHERE deleted = 0 AND payment_no = #{paymentNo} LIMIT 1")
    PaymentEntity selectByPaymentNo(@Param("paymentNo") String paymentNo);

    @Select("SELECT * FROM pay_payment WHERE deleted = 0 AND order_id = #{orderId}"
            + " AND status = 'PROCESSING' ORDER BY id DESC LIMIT 1")
    PaymentEntity selectProcessingByOrder(@Param("orderId") Long orderId);

    @Select("SELECT status FROM pay_payment WHERE deleted = 0 AND order_id = #{orderId}"
            + " ORDER BY id DESC LIMIT 1")
    String selectLatestStatus(@Param("orderId") Long orderId);

    /** 回调置成功（条件更新，PROCESSING→SUCCESS）。 */
    @Update("UPDATE pay_payment SET status = 'SUCCESS', channel_txn_no = #{txnNo}, paid_at = NOW(3),"
            + " callback_raw = #{raw}, version = version + 1"
            + " WHERE id = #{id} AND deleted = 0 AND status = 'PROCESSING'")
    int updateSuccess(@Param("id") Long id, @Param("txnNo") String txnNo, @Param("raw") String raw);

    /** 该单最新一笔 OFFLINE 流水（登记/复核判据都取它；无则 NULL）。 */
    @Select("SELECT * FROM pay_payment WHERE deleted = 0 AND order_id = #{orderId}"
            + " AND channel = 'OFFLINE' ORDER BY id DESC LIMIT 1")
    PaymentEntity selectLatestOfflineByOrder(@Param("orderId") Long orderId);

    /** 线下复核置成功（CAS 同 updateSuccess，另落复核人；callback_raw 记操作来源）。 */
    @Update("UPDATE pay_payment SET status = 'SUCCESS', channel_txn_no = #{txnNo},"
            + " confirmed_by = #{confirmedBy}, paid_at = NOW(3), callback_raw = '线下复核确认', version = version + 1"
            + " WHERE id = #{id} AND deleted = 0 AND status = 'PROCESSING'")
    int updateOfflineSuccess(@Param("id") Long id, @Param("txnNo") String txnNo,
                             @Param("confirmedBy") Long confirmedBy);
}
