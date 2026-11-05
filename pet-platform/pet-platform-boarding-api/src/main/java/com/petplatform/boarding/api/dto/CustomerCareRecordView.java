package com.petplatform.boarding.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** C 端护理记录（脱敏：无 caretakerId，仅可能有的展示信息）。 */
@Data
@Builder
public class CustomerCareRecordView {

    private Long id;
    private LocalDate careDate;
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
