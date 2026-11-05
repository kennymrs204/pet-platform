package com.petplatform.fulfillment.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.petplatform.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 遛狗 GPS 轨迹（ful_walk_track，V16；一次服务一条、保存后不可覆盖）。
 *
 * <p>{@code points} 是服务端清洗后的有效点 JSON（[[lat,lng,atMillis],...]）；
 * distanceSec/distanceM/pointCount 均在保存时由 {@code TrackGeometry} 派生并落库。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ful_walk_track")
public class WalkTrackEntity extends BaseEntity {

    private Long appointmentId;
    private Long petId;
    private Long storeId;
    private Long employeeId;
    private String trackId;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Integer durationSec;
    private Integer pausedSec;
    private Integer distanceM;
    private Integer pointCount;
    private String points;
}
