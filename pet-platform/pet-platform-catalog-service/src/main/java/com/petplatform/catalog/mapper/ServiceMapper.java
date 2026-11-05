package com.petplatform.catalog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.catalog.domain.ServiceEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ServiceMapper extends BaseMapper<ServiceEntity> {
}
