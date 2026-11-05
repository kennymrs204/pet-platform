package com.petplatform.pet.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 健康档案一次实际变更（只读）。
 * 只由 append-only 变更日志产生，绝不根据当前档案推导历史。
 */
@Data
@Builder
public class PetHealthChangeView {

    private Long id;
    private LocalDateTime changedAt;
    private String operatorType;
    private Long operatorId;
    private String operatorName;
    private String operatorRole;
    /** 本次实际变更字段：allergies/chronicDiseases/specialNotes */
    private List<String> changedFields;
    private PetHealthSnapshotView before;
    private PetHealthSnapshotView after;
}
