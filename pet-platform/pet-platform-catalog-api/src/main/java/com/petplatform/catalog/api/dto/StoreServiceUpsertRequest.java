package com.petplatform.catalog.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 门店上架服务新增/修改（第八阶段 §4.3：POST/PUT /api/v1/store/store-services）。
 * storeId 不进出参——一律取 X-Store-Id 上下文（ADR-008 §1.3，防越权改别店）。
 */
@Data
public class StoreServiceUpsertRequest {

    @NotNull(message = "服务必填")
    private Long serviceId;

    /** 价格（分），ADR-003-14。 */
    @NotNull(message = "价格必填")
    @Min(value = 0, message = "价格不能为负")
    private Long price;

    /** 门店实际时长（分钟），须为 30 的倍数（ADR-005-2，Service 层校验）。 */
    @NotNull(message = "服务时长必填")
    @Min(value = 30, message = "服务时长至少30分钟")
    private Integer durationMin;

    @Pattern(regexp = "^(BATH_STATION|GROOMING_STATION|EXAM_ROOM|KENNEL_SMALL|KENNEL_MEDIUM|KENNEL_LARGE|CAT_ROOM)$",
            message = "资源类型不合法")
    private String requiredResourceType;

    /** null=继承平台服务默认（cat_store_service.allow_user_pick_employee NULL 语义）。 */
    private Boolean allowUserPickEmployee;

    @NotNull(message = "免取消小时数必填")
    @Min(value = 0, message = "免取消小时数不能为负")
    private Integer cancelFreeHours;

    @NotNull(message = "取消扣费比例必填")
    @Min(value = 0, message = "取消扣费比例不能为负")
    @Max(value = 100, message = "取消扣费比例不能超过100")
    private Integer cancelPenaltyRate;

    @Pattern(regexp = "^(ON|OFF)$", message = "状态仅支持 ON/OFF")
    private String status;
}
