package com.petplatform.pet.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * B 端宠物列表查询（ADR-014）。storeId 不在入参——只认服务端已校验的 X-Store-Id 上下文。
 */
@Data
public class StorePetQuery {

    @Pattern(regexp = "^$|^(DOG|CAT|OTHER)$", message = "物种筛选仅支持DOG/CAT/OTHER")
    private String species;

    @Pattern(regexp = "^$|^(NORMAL|DECEASED|ARCHIVED)$", message = "状态筛选仅支持NORMAL/DECEASED/ARCHIVED")
    private String status;

    @Size(max = 30, message = "关键词不超过30字")
    private String keyword;

    @Min(value = 1, message = "页码从1开始")
    private int pageNum = 1;

    @Min(value = 1, message = "每页至少1条")
    @Max(value = 100, message = "每页最多100条")
    private int pageSize = 20;
}
