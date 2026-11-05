package com.petplatform.boarding.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 入住可选笼位（ADR-018 跨模块只读投影：资源表 Owner=schedule，经 ResourceOccupancyApi 组合出数）。
 * freeForSpan=false 表示该笼位在目标区间内已有 DAY 占用——前端置灰但保留可见，
 * 是否真能入住仍由 checkin 的权威校验裁决（并发抢占由 72001 兜底）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KennelOptionView {

    private Long id;
    /** KENNEL_SMALL/KENNEL_MEDIUM/KENNEL_LARGE/CAT_ROOM */
    private String type;
    private String name;
    /** 查询区间起（含） */
    private java.time.LocalDate spanFrom;
    /** 查询区间止（不含） */
    private java.time.LocalDate spanToExclusive;
    private Boolean freeForSpan;
}
