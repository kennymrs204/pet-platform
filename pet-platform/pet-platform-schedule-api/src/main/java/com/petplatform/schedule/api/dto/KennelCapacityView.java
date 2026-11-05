package com.petplatform.schedule.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 笼位容量口径（ADR-005-7：按日占用数；total 只计 AVAILABLE 资源）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KennelCapacityView {

    /** KENNEL_SMALL/KENNEL_MEDIUM/KENNEL_LARGE/CAT_ROOM */
    private String type;
    private long total;
    private long occupied;
    private long available;
}
