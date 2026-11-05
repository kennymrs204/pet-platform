package com.petplatform.appointment.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * sys_config 只读（app 横切表，先例 pet 模块，ADR-014 §1 TODO 同）。
 */
@Mapper
public interface AppointmentConfigMapper {

    @Select("SELECT config_value FROM sys_config WHERE config_key = #{key} LIMIT 1")
    String valueOf(@Param("key") String key);
}
