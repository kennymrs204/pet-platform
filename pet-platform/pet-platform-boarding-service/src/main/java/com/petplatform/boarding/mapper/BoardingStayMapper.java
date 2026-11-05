package com.petplatform.boarding.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.petplatform.boarding.domain.BoardingStayEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 入住单读写（ADR-015 §1）：状态写操作 = "WHERE status=期望前态 ∧ store_id"条件更新，
 * 影响行数 0 即并发/重复/非法迁移，affected-row 是唯一判定依据（禁止先查再写）。
 */
@Mapper
public interface BoardingStayMapper extends BaseMapper<BoardingStayEntity> {

    @Update("UPDATE boa_boarding_stay SET status = 'CHECKED_IN', kennel_resource_id = #{kennelResourceId},"
            + " caretaker_id = #{caretakerId}, special_instructions = #{specialInstructions},"
            + " actual_checkin_at = NOW(3), version = version + 1"
            + " WHERE id = #{id} AND store_id = #{storeId} AND status = 'EXPECTED' AND deleted = 0")
    int markCheckedIn(@Param("id") Long id, @Param("storeId") Long storeId,
                      @Param("kennelResourceId") Long kennelResourceId,
                      @Param("caretakerId") Long caretakerId,
                      @Param("specialInstructions") String specialInstructions);

    /**
     * 在住单补派/改派主责护理员：与 {@link #markCheckedIn} 同一条"条件更新即判定"的规矩
     * （WHERE 带 id+store_id+在住状态，0 行=状态已被他人推进），不改 status、不碰笼位。
     */
    @Update("UPDATE boa_boarding_stay SET caretaker_id = #{caretakerId}, version = version + 1"
            + " WHERE id = #{id} AND store_id = #{storeId} AND status IN ('CHECKED_IN', 'DAILY_CARE')"
            + " AND deleted = 0")
    int reassignCaretaker(@Param("id") Long id, @Param("storeId") Long storeId,
                          @Param("caretakerId") Long caretakerId);

    @Update("UPDATE boa_boarding_stay SET status = 'CHECKED_OUT', actual_checkout_at = NOW(3),"
            + " version = version + 1"
            + " WHERE id = #{id} AND store_id = #{storeId} AND status IN ('CHECKED_IN', 'DAILY_CARE')"
            + " AND deleted = 0")
    int markCheckedOut(@Param("id") Long id, @Param("storeId") Long storeId);

    @Update("UPDATE boa_boarding_stay SET status = 'DAILY_CARE', version = version + 1"
            + " WHERE id = #{id} AND status = 'CHECKED_IN' AND deleted = 0")
    int markDailyCare(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM boa_boarding_stay WHERE deleted = 0 AND appointment_id = #{appointmentId}")
    long countByAppointment(@Param("appointmentId") Long appointmentId);

    /** 预约取消撤单（ADR-023 §3）：EXPECTED→逻辑删（delval=id 惯例），B 端入住闸随之 10004。 */
    @Update("UPDATE boa_boarding_stay SET deleted = id, version = version + 1"
            + " WHERE appointment_id = #{appointmentId} AND deleted = 0 AND status = 'EXPECTED'")
    int logicalDeleteExpectedByAppointment(@Param("appointmentId") Long appointmentId);

    /** C 端我的住单分页（ADR-021）：归属=预约 id 集；statuses 空=全部；checkin_date desc→id desc。 */
    @Select("<script>"
            + "SELECT b.id, b.appointment_id, b.pet_id, b.store_id, b.checkin_date, b.checkout_date,"
            + " b.actual_checkin_at, b.actual_checkout_at, b.status, b.caretaker_id, b.special_instructions"
            + " FROM boa_boarding_stay b WHERE b.deleted = 0"
            + " AND b.appointment_id IN <foreach collection=\"apptIds\" item=\"aid\" open=\"(\" separator=\",\" close=\")\">#{aid}</foreach>"
            + " <if test=\"statuses != null and statuses.size() > 0\">AND b.status IN"
            + " <foreach collection=\"statuses\" item=\"st\" open=\"(\" separator=\",\" close=\")\">#{st}</foreach></if>"
            + " ORDER BY b.checkin_date DESC, b.id DESC LIMIT #{offset}, #{size}"
            + "</script>")
    java.util.List<BoardingStayEntity> selectCustomerStays(@Param("apptIds") java.util.Collection<Long> apptIds,
                                                           @Param("statuses") java.util.Collection<String> statuses,
                                                           @Param("offset") int offset, @Param("size") int size);

    @Select("<script>"
            + "SELECT COUNT(*) FROM boa_boarding_stay b WHERE b.deleted = 0"
            + " AND b.appointment_id IN <foreach collection=\"apptIds\" item=\"aid\" open=\"(\" separator=\",\" close=\")\">#{aid}</foreach>"
            + " <if test=\"statuses != null and statuses.size() > 0\">AND b.status IN"
            + " <foreach collection=\"statuses\" item=\"st\" open=\"(\" separator=\",\" close=\")\">#{st}</foreach></if>"
            + "</script>")
    long countCustomerStays(@Param("apptIds") java.util.Collection<Long> apptIds,
                            @Param("statuses") java.util.Collection<String> statuses);

    default BoardingStayEntity byAppointment(Long appointmentId) {
        return selectOne(Wrappers.<BoardingStayEntity>lambdaQuery()
                .eq(BoardingStayEntity::getAppointmentId, appointmentId)
                .last("LIMIT 1"));
    }
}
