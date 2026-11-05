package com.petplatform.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.user.domain.UserPetEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserPetMapper extends BaseMapper<UserPetEntity> {
}
