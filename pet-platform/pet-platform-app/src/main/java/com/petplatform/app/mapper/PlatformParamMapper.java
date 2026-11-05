package com.petplatform.app.mapper;

import com.petplatform.app.service.PlatformParamRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 平台参数只读 mapper（sys_config 表 Owner 本就是 app 装配层，V1:638 注释）。
 * 设置页只展示不修改——P8 §4.5 :267 的 ADMIN 写端点 /admin/configs 尚未实现。
 */
@Mapper
public interface PlatformParamMapper {

    @Select("""
            SELECT config_key AS configKey, config_value AS configValue, remark
            FROM sys_config
            ORDER BY id ASC
            """)
    List<PlatformParamRow> selectAll();

    /** 单值读取（退款审批限额等运行期判定用）；key 不存在返回 null。 */
    @Select("SELECT config_value FROM sys_config WHERE config_key = #{key} LIMIT 1")
    String selectValue(@Param("key") String key);
}
