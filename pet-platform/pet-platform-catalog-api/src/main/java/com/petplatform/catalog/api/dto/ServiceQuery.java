package com.petplatform.catalog.api.dto;

import com.petplatform.common.result.PageQuery;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 平台服务目录分页查询（ADMIN）。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ServiceQuery extends PageQuery {

    @Size(max = 50, message = "关键字不超过50字")
    private String keyword;

    @Size(max = 32, message = "分类不超过32字")
    private String category;

    @Pattern(regexp = "^(ON|OFF)$", message = "状态仅支持 ON/OFF")
    private String status;
}
