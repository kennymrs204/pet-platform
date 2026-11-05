package com.petplatform.pet.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.pet.domain.PetEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PetMapper extends BaseMapper<PetEntity> {

    /** 本店代建宠物 id（ADR-058 增量：B 端可见范围 = 预约关联 ∪ 在店托管 ∪ 本店代建）。 */
    @Select("SELECT id FROM pet_pet WHERE deleted = 0 AND origin_store_id = #{storeId}")
    List<Long> selectIdsByOriginStore(@Param("storeId") Long storeId);

    /** 健康写入/删除的宠物行锁；锁顺序固定为 健康行 → 宠物行。 */
    @Select("SELECT * FROM pet_pet WHERE id = #{petId} AND deleted = 0 LIMIT 1 FOR UPDATE")
    PetEntity selectByIdForUpdate(@Param("petId") Long petId);
}
