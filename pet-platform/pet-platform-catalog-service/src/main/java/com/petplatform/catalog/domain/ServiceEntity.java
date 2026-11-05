package com.petplatform.catalog.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.petplatform.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 平台服务目录（cat_service）：无价格，定价只发生在门店上架层。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cat_service")
public class ServiceEntity extends BaseEntity {

    private String code;
    private String name;
    private String description;
    private String category;
    private Integer requiredDurationMin;
    /** INSTANT/MULTI_DAY（ADR-003-4） */
    private String fulfillmentType;
    private Boolean allowUserPickEmployee;
    /** ON/OFF */
    private String status;
}
