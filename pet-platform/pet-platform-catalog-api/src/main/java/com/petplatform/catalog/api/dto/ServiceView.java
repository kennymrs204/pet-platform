package com.petplatform.catalog.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 平台服务目录视图（ADMIN 维护用）。 */
@Data
@Builder
public class ServiceView {

    private Long id;
    private String code;
    private String name;
    private String description;
    private String category;
    private Integer requiredDurationMin;
    private String fulfillmentType;
    private Boolean allowUserPickEmployee;
    private String status;
    private List<SkillRef> skills;
}
