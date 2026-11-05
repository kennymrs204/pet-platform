package com.petplatform.catalog.api.dto;

import com.petplatform.common.result.PageQuery;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 门店上架服务查询（B 端 / C 端共用）。
 *
 * <p>B 端（{@code /api/v1/store/store-services}）<b>忽略</b> {@code storeId} 字段，
 * 一律以 X-Store-Id 上下文为准（ADR-008 §1.3 数据范围）；C 端必须显式传 storeId。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StoreServiceQuery extends PageQuery {

    private Long storeId;

    @Size(max = 50, message = "关键字不超过50字")
    private String keyword;

    @Pattern(regexp = "^(ON|OFF)$", message = "状态仅支持 ON/OFF")
    private String status;
}
