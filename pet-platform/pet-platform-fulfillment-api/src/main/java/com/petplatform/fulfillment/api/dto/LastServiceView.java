package com.petplatform.fulfillment.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 宠物最近一次服务记录摘要（ADR-014，不含照片/轨迹等明细）。 */
@Data
@Builder
public class LastServiceView {

    private Long petId;
    private Long appointmentId;
    /** BATH/WALK */
    private String type;
    private LocalDateTime finishedAt;
}
