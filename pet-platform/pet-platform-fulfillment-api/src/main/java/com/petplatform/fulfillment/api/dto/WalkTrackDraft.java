package com.petplatform.fulfillment.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 遛狗轨迹的保存入参（2026-10-24）。
 *
 * <p>points 是客户端采集的**原始**坐标（lat/lng/采集毫秒时间戳）；服务端会做范围校验、
 * 时间序校验、重复点与跳点剔除，并据此派生 distance/duration/pointCount 后落库——
 * 客户端提交的任何"我算好了多少公里"都不被采信。
 *
 * @param trackId   客户端幂等键（同一条轨迹重试必须是同一个值）
 * @param pausedSec 用户累计暂停时长；只用于扣减实际记录时长，不参与距离
 */
@Data
@Builder
public class WalkTrackDraft {

    private Long appointmentId;
    private Long petId;
    private Long storeId;
    private Long employeeId;
    private String trackId;
    private int pausedSec;
    private List<WalkTrackPoint> points;
}
