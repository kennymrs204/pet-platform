package com.petplatform.boarding.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.boarding.domain.DailyCareRecordEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface DailyCareRecordMapper extends BaseMapper<DailyCareRecordEntity> {

    /** 全店某业务日的护理记录（含宠物 id 供装配层回填名字）。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ActivityRow {
        private Long recordId;
        private Long stayId;
        private Long petId;
        private LocalDate careDate;
        private Long caretakerId;
        private String feeding;
        private String watering;
        private String walk;
        private String observation;
        private Boolean abnormalFlag;
        private String abnormalDesc;
    }

    /**
     * 两表 Owner 同为 boarding（{@code boa_daily_care_record} × {@code boa_boarding_stay}），
     * 所以这条 JOIN 在模块内合法；护理员范围**下推进 WHERE**，不是取回再 filter——
     * 后者与 LIMIT 的先后顺序会让任务角色的单静默消失（ADR-032 §1）。
     */
    @Select("<script>"
            + "SELECT r.id AS recordId, r.boarding_stay_id AS stayId, s.pet_id AS petId, r.care_date AS careDate,"
            + " r.caretaker_id AS caretakerId, r.feeding AS feeding, r.watering AS watering, r.walk AS walk,"
            + " r.observation AS observation, r.abnormal_flag AS abnormalFlag, r.abnormal_desc AS abnormalDesc"
            + " FROM boa_daily_care_record r"
            + " JOIN boa_boarding_stay s ON s.id = r.boarding_stay_id AND s.deleted = 0"
            + " WHERE r.deleted = 0 AND s.store_id = #{storeId} AND r.care_date = #{date}"
            + " <if test='caretakerId != null'> AND s.caretaker_id = #{caretakerId} </if>"
            + " ORDER BY r.id DESC LIMIT #{limit}"
            + "</script>")
    List<ActivityRow> selectStoreActivity(@Param("storeId") Long storeId, @Param("date") LocalDate date,
                                         @Param("caretakerId") Long caretakerId, @Param("limit") int limit);
}
