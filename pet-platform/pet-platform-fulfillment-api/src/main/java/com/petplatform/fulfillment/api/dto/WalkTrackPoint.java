package com.petplatform.fulfillment.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个轨迹点：WGS84 坐标 + 采集时间（epoch millis，来自设备时钟）+ 定位精度（米）。
 *
 * <p>{@code accuracyM} 来自浏览器 Geolocation 的 {@code coords.accuracy}（水平精度半径，1σ，单位米）；
 * {@code 0} 或缺失表示"客户端没有提供精度"（历史数据/不支持该字段的设备），按未知处理，
 * 不因此丢弃点。边界校验在 API 入口做第一道；服务端派生前还会再清洗（精度/重复/跳点/时间倒序）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WalkTrackPoint {

    @DecimalMin(value = "-90", message = "纬度越界")
    @DecimalMax(value = "90", message = "纬度越界")
    private double lat;

    @DecimalMin(value = "-180", message = "经度越界")
    @DecimalMax(value = "180", message = "经度越界")
    private double lng;

    @Positive(message = "采集时间必须为正（epoch millis）")
    private long atMillis;

    /** 水平精度（米）：0=未知；有限非负数，NaN/无穷/负数由服务端按未知处理 */
    @DecimalMin(value = "0", message = "定位精度不能为负")
    private double accuracyM;

    /** 兼容既有调用点与历史读取：不带精度时按未知（0）处理。 */
    public WalkTrackPoint(double lat, double lng, long atMillis) {
        this(lat, lng, atMillis, 0);
    }
}
