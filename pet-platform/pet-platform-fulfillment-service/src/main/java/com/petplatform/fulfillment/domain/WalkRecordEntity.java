package com.petplatform.fulfillment.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.petplatform.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 遛狗履约记录（ful_walk_record，append-only）。
 *
 * <p>distanceM/trackSummary/poopCount 可空且本轮不填——门店 UI 没有产生它们的入口，
 * 写 0 等于把"没记录"伪装成"记录了零"。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ful_walk_record")
public class WalkRecordEntity extends BaseEntity {

    private Long appointmentId;
    private Long petId;
    private Long employeeId;
    private String executorRole;
    private Boolean byManager;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer durationMin;
    private Integer distanceM;
    private String trackSummary;
    private String photos;
    private Integer poopCount;
    private String remark;
}
