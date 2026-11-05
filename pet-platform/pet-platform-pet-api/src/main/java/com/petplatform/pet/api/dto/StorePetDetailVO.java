package com.petplatform.pet.api.dto;

import lombok.Builder;
import lombok.Data;

/**
 * B 端宠物详情（ADR-014）= 基础档案 + 最近服务/托管摘要 + 健康档案。
 * health：仅当当前上下文持 pet:health:view 时返回（DOG_WALKER 无此码 → null），
 * 且只含 pet_health_info 四字段；诊疗/处方/报告归 medical，不经本端点泄露。
 */
@Data
@Builder
public class StorePetDetailVO {

    private PetVO pet;
    private StorePetItemVO.HealthSummaryVO healthSummary;
    private StorePetItemVO.LastServiceVO lastService;
    private StorePetItemVO.BoardingBriefVO boarding;
    private PetHealthVO health;
}
