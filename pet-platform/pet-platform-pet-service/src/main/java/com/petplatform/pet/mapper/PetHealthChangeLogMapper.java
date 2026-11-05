package com.petplatform.pet.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.pet.domain.PetHealthChangeLogEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PetHealthChangeLogMapper extends BaseMapper<PetHealthChangeLogEntity> {
}
