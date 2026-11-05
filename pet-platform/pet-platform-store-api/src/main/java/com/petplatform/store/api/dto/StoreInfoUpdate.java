package com.petplatform.store.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 门店资料更新（ADR-020 §3 白名单）：只有这些字段可写。
 * storeId 不在体内——一律取 X-Store-Id 上下文；store_type/status 不可经本请求修改。
 */
@Data
public class StoreInfoUpdate {

    @NotBlank(message = "门店名称不能为空")
    @Size(max = 100, message = "门店名称最多100字")
    private String name;

    @NotBlank(message = "联系电话不能为空")
    @Size(max = 20, message = "联系电话最多20位")
    private String phone;

    @NotBlank(message = "门店地址不能为空")
    @Size(max = 255, message = "门店地址最多255字")
    private String address;

    private BigDecimal latitude;
    private BigDecimal longitude;

    @NotNull(message = "营业开始时间必填")
    private LocalTime businessStartTime;

    @NotNull(message = "营业结束时间必填")
    private LocalTime businessEndTime;

    /** 午休起止必须成对出现（只给一头 → 10001） */
    private LocalTime breakStartTime;
    private LocalTime breakEndTime;

    @Size(max = 500, message = "门店公告最多500字")
    private String announcement;
}
