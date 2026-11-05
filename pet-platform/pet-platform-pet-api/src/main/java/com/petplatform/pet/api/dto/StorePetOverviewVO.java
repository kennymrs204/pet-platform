package com.petplatform.pet.api.dto;

import lombok.Builder;
import lombok.Data;

/**
 * B 端宠物数据概览（ADR-014）。计数均基于当前门店可见范围；无数据返回 0，不报错。
 */
@Data
@Builder
public class StorePetOverviewVO {

    /** 可见宠物总数 */
    private long totalPets;
    private long dogCount;
    private long catCount;
    /** 当前在店托管数（boarding 口径，与可见范围独立） */
    private long inBoardingCount;
}
