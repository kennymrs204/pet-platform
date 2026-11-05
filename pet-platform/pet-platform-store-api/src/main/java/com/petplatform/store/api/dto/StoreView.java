package com.petplatform.store.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalTime;

/** 门店视图（ADR-016：status=OPEN 判定 + 营业/休息时段，候选槽生成输入）。 */
@Data
@Builder
public class StoreView {

    private Long id;
    private String name;
    /** OPEN/SUSPENDED/CLOSED */
    private String status;
    /** NORMAL/PLATFORM_HQ */
    private String storeType;
    private LocalTime businessStart;
    private LocalTime businessEnd;
    private LocalTime breakStart;
    private LocalTime breakEnd;
}
