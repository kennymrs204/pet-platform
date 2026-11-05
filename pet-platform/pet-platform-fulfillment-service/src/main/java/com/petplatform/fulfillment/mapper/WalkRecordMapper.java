package com.petplatform.fulfillment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.fulfillment.domain.WalkRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 遛狗记录写入（ADR-048）+ 照片追加（2026-10-24）。 */
@Mapper
public interface WalkRecordMapper extends BaseMapper<WalkRecordEntity> {

    /**
     * 按预约锁行后读（补照片的串行点）：先锁再读再写，两个并发追加者不会互相覆盖对方的 key。
     * 表上有 uk_appt(appointment_id)，一条预约至多一条记录。
     */
    @Select("SELECT * FROM ful_walk_record WHERE appointment_id = #{appointmentId} AND deleted = 0"
            + " LIMIT 1 FOR UPDATE")
    WalkRecordEntity selectByAppointmentForUpdate(@Param("appointmentId") Long appointmentId);

    @Update("UPDATE ful_walk_record SET photos = #{photos}, updated_at = NOW(3)"
            + " WHERE id = #{id} AND deleted = 0")
    int updatePhotos(@Param("id") Long id, @Param("photos") String photos);
}
