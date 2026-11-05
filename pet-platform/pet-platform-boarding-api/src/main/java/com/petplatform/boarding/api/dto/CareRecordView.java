package com.petplatform.boarding.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** 护理记录视图（B 端；C 端经 CustomerCareRecordView 脱敏另出，ADR-015 §4）。 */
@Data
@Builder
public class CareRecordView {

    private Long id;
    private LocalDate careDate;
    private Long caretakerId;
    /** 护理员姓名，由 B 端列表批量回填；查无/已删为 null */
    private String caretakerName;
    private CareRecordRequest.Feeding feeding;
    private CareRecordRequest.Watering watering;
    private CareRecordRequest.Walk walk;
    private String observation;
    private List<String> photos;
    /** presigned 读取 URL（ADR-029；未启用存储为空数组，与 photos 同序） */
    private List<String> photoUrls;
    private Boolean abnormalFlag;
    private String abnormalDesc;
}
