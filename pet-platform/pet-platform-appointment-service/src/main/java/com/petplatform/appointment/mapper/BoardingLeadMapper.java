package com.petplatform.appointment.mapper;

import com.petplatform.appointment.api.dto.BoardingLeadView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * ADR-030 只读投影（不建实体、不继承 BaseMapper）。
 *
 * <p>自写 SQL 而非 MP lambda wrapper：纯 Mockito 单测下 {@code Wrappers.lambdaQuery()} 会因
 * TableInfo 缓存未建而抛 "can not find lambda cache"（ADR-021 的 BoardingQueryMapper 先例）。
 */
@Mapper
public interface BoardingLeadMapper {

    @Select("""
            SELECT id, appointment_no AS appointmentNo, user_id AS userId, pet_id AS petId,
                   boarding_checkin_date AS boardingCheckinDate,
                   boarding_checkout_date AS boardingCheckoutDate,
                   service_name_snapshot AS serviceName, price_snapshot AS priceSnapshot,
                   status, expire_at AS expireAt, created_at AS createdAt
            FROM apt_appointment
            WHERE deleted = 0
              AND store_id = #{storeId}
              AND status = 'PENDING_PAYMENT'
              AND boarding_checkin_date IS NOT NULL
              AND boarding_checkin_date BETWEEN #{from} AND #{to}
            ORDER BY boarding_checkin_date ASC, id ASC
            LIMIT #{limit}
            """)
    List<BoardingLeadView> selectPending(@Param("storeId") Long storeId, @Param("from") LocalDate from,
                                        @Param("to") LocalDate to, @Param("limit") int limit);
}
