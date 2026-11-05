package com.petplatform.schedule.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 笼位资源视图（ADR-015；跨店查询按不存在处理，防探测）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KennelResourceView {

    private Long id;
    private Long storeId;
    /** BATH_STATION/GROOMING_STATION/EXAM_ROOM/KENNEL_SMALL/KENNEL_MEDIUM/KENNEL_LARGE/CAT_ROOM */
    private String type;
    private String name;
    /** AVAILABLE/MAINTENANCE/DISABLED */
    private String status;
}
