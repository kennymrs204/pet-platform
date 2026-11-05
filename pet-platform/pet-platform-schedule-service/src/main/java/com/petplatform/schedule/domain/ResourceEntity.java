package com.petplatform.schedule.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.petplatform.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 门店资源（sch_resource）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sch_resource")
public class ResourceEntity extends BaseEntity {

    private Long storeId;
    /** BATH_STATION/GROOMING_STATION/EXAM_ROOM/KENNEL_SMALL/KENNEL_MEDIUM/KENNEL_LARGE/CAT_ROOM */
    private String type;
    private String name;
    /** AVAILABLE/MAINTENANCE/DISABLED */
    private String status;
}
