package com.petplatform.pet.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.pet.domain.PetHealthEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PetHealthMapper extends BaseMapper<PetHealthEntity> {

    /**
     * 健康档案写入前的行锁。所有健康更新都必须先走本方法，才能在事务内
     * 把"读旧值 → 版本比较 → 写新值/日志"串行化。
     * 不带 MyBatis-Plus 逻辑删除自动条件，故显式 deleted=0。
     */
    @Select("SELECT * FROM pet_health_info WHERE pet_id = #{petId} AND deleted = 0 LIMIT 1 FOR UPDATE")
    PetHealthEntity selectByPetIdForUpdate(@Param("petId") Long petId);

    /**
     * 显式推进 updated_at：不能依赖 MyBatis updateById（实体已带旧 updated_at 时会把它写回，
     * 且同毫秒内更新可能得到相同时间戳）。GREATEST 保证每次成功写入的版本严格前进。
     */
    @Update("""
            UPDATE pet_health_info
               SET allergies = #{allergies,jdbcType=VARCHAR},
                   chronic_diseases = #{chronicDiseases,jdbcType=VARCHAR},
                   special_notes = #{specialNotes,jdbcType=VARCHAR},
                   updated_by_role = #{updatedByRole,jdbcType=VARCHAR},
                   updated_at = GREATEST(CURRENT_TIMESTAMP(3),
                                         TIMESTAMPADD(MICROSECOND, 1000,
                                                      COALESCE(updated_at, CURRENT_TIMESTAMP(3))))
             WHERE pet_id = #{petId} AND deleted = 0
            """)
    int updateHealthSnapshot(PetHealthEntity health);
}
