package com.petplatform.order.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单统计只读投影（不建实体，纯读 ord_order/ord_refund；两表同属 order 模块）。
 * 时间窗口由服务层按业务时区算好传入（左闭右开），SQL 不做时区运算。
 */
@Mapper
public interface OrderStatsMapper {

    @Select("""
            SELECT COUNT(*) FROM ord_order
            WHERE deleted = 0 AND store_id = #{storeId}
              AND created_at >= #{from} AND created_at < #{to}
              AND status <> 'CANCELLED'
            """)
    long countOrders(@Param("storeId") Long storeId,
                     @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("""
            SELECT COALESCE(SUM(paid_amount), 0) FROM ord_order
            WHERE deleted = 0 AND store_id = #{storeId}
              AND paid_at IS NOT NULL AND paid_at >= #{from} AND paid_at < #{to}
              AND status <> 'CANCELLED'
            """)
    long sumPaid(@Param("storeId") Long storeId,
                 @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("""
            SELECT COALESCE(SUM(r.amount), 0)
            FROM ord_refund r
            JOIN ord_order o ON o.id = r.order_id AND o.deleted = 0
            WHERE r.deleted = 0 AND r.status = 'SUCCESS'
              AND o.store_id = #{storeId}
              AND r.refunded_at IS NOT NULL AND r.refunded_at >= #{from} AND r.refunded_at < #{to}
              AND o.status <> 'CANCELLED'
            """)
    long sumRefund(@Param("storeId") Long storeId,
                   @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 按客户聚合累计实付（ADR-017 §6；口径同 sumPaid，去掉日期窗、按 user_id 分组，不扣退款）。 */
    @Select("""
            <script>
            SELECT o.user_id AS user_id, COALESCE(SUM(o.paid_amount), 0) AS paid
            FROM ord_order o
            WHERE o.deleted = 0 AND o.store_id = #{storeId}
              AND o.paid_at IS NOT NULL AND o.status &lt;&gt; 'CANCELLED'
              AND o.user_id IN
            <foreach collection="userIds" item="uid" open="(" separator="," close=")">#{uid}</foreach>
            GROUP BY o.user_id
            </script>
            """)
    List<UserPaidRow> selectPaidByUsers(@Param("storeId") Long storeId,
                                        @Param("userIds") java.util.Collection<Long> userIds);

    class UserPaidRow {
        private Long userId;
        private long paid;

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public long getPaid() { return paid; }
        public void setPaid(long paid) { this.paid = paid; }
    }
}
