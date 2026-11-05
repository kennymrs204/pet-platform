package com.petplatform.pet.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * sys_config 只读（第七阶段：app 横切表，非业务域表，不触发"跨模块查表"禁令，ADR-014 §1）。
 * TODO 配置读取若被多模块复用，应上收为独立配置端口；当前仅 pet 使用，就近只读。
 */
@Mapper
public interface SysConfigMapper {

    @Select("SELECT config_value FROM sys_config WHERE config_key = #{key} LIMIT 1")
    String valueOf(@Param("key") String key);
}
