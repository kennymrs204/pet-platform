package com.petplatform.app.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** sys_config 投影行（MyBatis 部分列映射需无参构造 + setter，勿用 @Builder-only）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlatformParamRow {

    private String configKey;
    private String configValue;
    private String remark;
}
