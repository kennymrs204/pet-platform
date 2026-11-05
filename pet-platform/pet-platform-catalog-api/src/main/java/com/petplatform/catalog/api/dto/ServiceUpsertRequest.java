package com.petplatform.catalog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 平台服务目录新增/修改（第八阶段 §4.5：POST/PUT /api/v1/admin/services）。
 *
 * <p>不含价格：Service 是无价目录，定价只发生在 cat_store_service（第七阶段 §cat_service 注记）。
 */
@Data
public class ServiceUpsertRequest {

    @NotBlank(message = "服务编码必填")
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$", message = "服务编码须为大写字母开头的字母数字下划线组合，2-32位")
    private String code;

    @NotBlank(message = "服务名称必填")
    @Size(max = 50, message = "服务名称不超过50字")
    private String name;

    @Size(max = 500, message = "服务说明不超过500字")
    private String description;

    @Size(max = 32, message = "服务分类不超过32字")
    private String category;

    /** 标准时长（分钟），门店可覆盖；须 30 分钟对齐（ADR-005-2 槽位离散化）。 */
    @NotNull(message = "标准时长必填")
    @Positive(message = "标准时长须为正数")
    private Integer requiredDurationMin;

    @NotBlank(message = "履约类型必填")
    @Pattern(regexp = "^(INSTANT|MULTI_DAY)$", message = "履约类型仅支持 INSTANT/MULTI_DAY")
    private String fulfillmentType;

    @NotNull(message = "是否允许用户指定员工必填")
    private Boolean allowUserPickEmployee;

    @Pattern(regexp = "^(ON|OFF)$", message = "状态仅支持 ON/OFF")
    private String status;

    /** 所需技能声明（全量替换）；required=false 表示可选加分技能。 */
    private List<SkillDeclaration> skills;

    @Data
    public static class SkillDeclaration {

        @NotNull(message = "技能id必填")
        private Long skillId;

        /** 缺省视为 true（必需）。 */
        private Boolean required;
    }
}
