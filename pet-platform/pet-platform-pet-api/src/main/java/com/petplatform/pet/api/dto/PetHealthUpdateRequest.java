package com.petplatform.pet.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 健康档案三字段整体覆盖请求（C 端与 B 端门店共用，ADR-014 后续增量）。
 * updatedByRole/操作人/门店均由后端按调用上下文写入，不接受客户端传入。
 */
@Data
public class PetHealthUpdateRequest {

    @Size(max = 20, message = "过敏项最多20条")
    private List<@Size(max = 100, message = "单个过敏项不超过100字") String> allergies;

    @Size(max = 20, message = "慢性病项最多20条")
    private List<@Size(max = 100, message = "单个慢性病项不超过100字") String> chronicDiseases;

    @Size(max = 500, message = "特殊说明不超过500字")
    private String specialNotes;

    /**
     * 客户端读取档案时得到的 updatedAt（毫秒精度版本令牌）。
     * 普通编辑（B/C 端）必填；仅目标尚无健康行、首次建档时允许为 null。
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss[.SSS]")
    private LocalDateTime expectedUpdatedAt;
}
