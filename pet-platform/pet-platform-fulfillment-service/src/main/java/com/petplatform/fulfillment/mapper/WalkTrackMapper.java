package com.petplatform.fulfillment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.fulfillment.domain.WalkTrackEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/** 遛狗轨迹读写（V16）。写入只有 INSERT——保存后不可覆盖由 uk_appt 唯一键兜底。 */
@Mapper
public interface WalkTrackMapper extends BaseMapper<WalkTrackEntity> {

    @Select("SELECT * FROM ful_walk_track WHERE appointment_id = #{appointmentId} AND deleted = 0 LIMIT 1")
    WalkTrackEntity selectByAppointment(@Param("appointmentId") Long appointmentId);

    @Select("<script>"
            + "SELECT * FROM ful_walk_track WHERE deleted = 0 AND appointment_id IN"
            + " <foreach collection=\"appointmentIds\" item=\"aid\" open=\"(\" separator=\",\" close=\")\">#{aid}</foreach>"
            + "</script>")
    List<WalkTrackEntity> selectByAppointments(@Param("appointmentIds") Collection<Long> appointmentIds);
}
