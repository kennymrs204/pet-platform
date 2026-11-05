package com.petplatform.fulfillment.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 遛狗轨迹摘要（读侧）。距离/时长/点数都是**保存时由服务端派生并持久化**的值，读侧只回显不重算。
 *
 * <p>{@code polyline} 是抽稀后的坐标序列（首尾保留、按步长取样，≤ {@code POLYLINE_MAX_POINTS}），
 * 只用于画示意路线；地图 SDK 项目当前未接入，前端用内联 SVG 渲染，不额外引依赖。
 */
@Data
@Builder
public class WalkTrackView {

    private Long id;
    private Long appointmentId;
    private String trackId;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    /** 实际记录时长（秒）= 末点 − 首点 − 暂停 */
    private int durationSec;
    private int pausedSec;
    /** 服务端累加、已剔除跳点（米） */
    private int distanceM;
    /** 有效点数（清洗后） */
    private int pointCount;
    /** 抽稀后的 [lat,lng] 序列；无有效点=空数组 */
    private List<double[]> polyline;
}
