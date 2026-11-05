package com.petplatform.catalog.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 门店上架服务视图：既是 web 出参，也是 {@link com.petplatform.catalog.api.CatalogQueryApi}
 * 交给 appointment / schedule 的判定输入（第二阶段 §catalog 暴露 api 原文）。
 */
@Data
@Builder
public class StoreServiceView {

    private Long id;
    private Long storeId;
    private Long serviceId;
    private String serviceCode;
    /** 服务名快照来源（appointment 创建时落 name/price/duration 快照，C4 裁决）。 */
    private String serviceName;
    private String description;
    private String category;
    private Long price;
    private Integer durationMin;
    private String fulfillmentType;
    private String requiredResourceType;
    /** 已解析的最终值：门店覆盖优先，否则继承平台服务默认。 */
    private Boolean allowUserPickEmployee;
    /** 门店是否做了覆盖（null=继承，true/false=覆盖值），供 B 端表单回填。 */
    private Boolean allowUserPickEmployeeOverride;
    private Integer cancelFreeHours;
    private Integer cancelPenaltyRate;
    private String status;
    private List<SkillRef> skills;
}
