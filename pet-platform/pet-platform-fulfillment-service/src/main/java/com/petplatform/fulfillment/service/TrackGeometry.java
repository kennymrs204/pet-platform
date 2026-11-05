package com.petplatform.fulfillment.service;

import com.petplatform.fulfillment.api.dto.WalkTrackPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * 轨迹清洗与里程派生（纯函数，**服务端唯一权威**）。
 *
 * <p>为什么放在服务端而不是前端算好再传：里程是"履约事实"的一部分，必须由持久化数据派生，
 * 且同一份算法不能在两处各写一遍（漂移后无法解释"为什么页面 1.4km、库里 1.1km"）。
 * 前端只负责采集坐标；这里负责剔除脏点、累加距离、算实际记录时长。
 *
 * <p>清洗规则（按顺序，任一命中即跳过该点）：
 * ① 坐标非有限数 / 纬度越界 ±90 / 经度越界 ±180 / 采集时间缺失；
 * ② 时间不递增（与上一个有效点同刻或倒序）——乱序点不能参与累加；
 * ③ **精度不可用**：{@code accuracyM > 200} 米（基站/室内粗定位）——这种点连"在哪个街区"都不可信；
 * ④ **跳点**：与上一个**可测距锚点**的隐含速度 > {@value #MAX_SPEED_MPS} m/s
 *    （≈108 km/h，步行/慢跑不可能达到，几乎都是定位漂移或基站切换）；
 * ⑤ 坐标与上一个有效点完全相同（重复点）。
 *
 * <p><b>精度门控（本轮新增）</b>：只有当一段位移**大于该段两端的定位误差**时，才把这段计入里程
 * （{@code segment >= max(MIN_SEGMENT_M, 端点精度)}）。这直接回应"误差不能累加成里程"：
 * 精度 30 米的点之间走 8 米属于噪声，走 60 米才是真实位移。
 * 精度在 {@value #MAX_ACCURACY_RELIABLE_M} 米以上但未达硬阈值的点：**保留在时间轴上但不计入里程、
 * 也不作为下一段的测距锚点**——这样既不会因为一个坏点丢掉整段轨迹（下一个好点仍与上一个好点比），
 * 也不会让坏点的几十米误差进入累计值。
 * 精度为 0/缺失（历史数据或设备不支持）按"未知"处理，退回原来的 1 米最小位移口径，保持读取兼容。
 */
public final class TrackGeometry {

    /** 单次请求点数上限（服务端硬闸，前端同值只做提前提示）。 */
    public static final int MAX_POINTS = 2000;
    /** 抽稀后返回给前端画线的点数上限。 */
    public static final int POLYLINE_MAX_POINTS = 200;
    /** 实际记录时长上限（12h），超过视为时间戳异常。 */
    public static final int MAX_DURATION_SEC = 12 * 3600;
    public static final double MAX_SPEED_MPS = 30.0;
    public static final double MIN_SEGMENT_M = 1.0;
    /**
     * 精度硬阈：超过即丢弃该点。手机在开阔地通常 5–20 米，城市峡谷 30–50 米；
     * 200 米以上已属基站/室内粗定位，不是"这一次走得不准"，而是"这个点不在附近"。
     */
    public static final double MAX_ACCURACY_HARD_M = 200.0;
    /**
     * 精度可信阈：50 米以内的点参与测距（并用自身精度做门控）；
     * 超过 50 米仍可能真实但不足以算里程，只保留时间轴。
     */
    public static final double MAX_ACCURACY_RELIABLE_M = 50.0;
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private TrackGeometry() {
    }

    /** 清洗结果：有效点序列 + 里程（米）+ 实际记录时长（秒，已扣暂停）。 */
    public record Cleaned(List<WalkTrackPoint> points, int distanceM, int durationSec) {
    }

    /**
     * @return 清洗结果；有效点不足 2 个返回 {@code null}（调用方按"轨迹数据不足"拒绝，不得伪造里程）
     */
    public static Cleaned sanitize(List<WalkTrackPoint> raw, int pausedSec) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<WalkTrackPoint> kept = new ArrayList<>();
        // 最近一个"可用于测距"的点：精度可信才更新。低精度点只进时间轴，不做锚点，
        // 这样下一个好点仍然与上一个好点比较——一个坏点不会把整段轨迹切碎。
        WalkTrackPoint anchor = null;
        double distance = 0;
        for (WalkTrackPoint p : raw) {
            if (p == null || !validCoord(p.getLat(), p.getLng()) || p.getAtMillis() <= 0) {
                continue;
            }
            double acc = accuracyOf(p);
            if (acc > MAX_ACCURACY_HARD_M) {
                continue; // 精度不可用（基站/室内粗定位）
            }
            if (kept.isEmpty()) {
                kept.add(p);
                if (acc <= MAX_ACCURACY_RELIABLE_M) {
                    anchor = p;
                }
                continue;
            }
            WalkTrackPoint lastKept = kept.get(kept.size() - 1);
            if (p.getAtMillis() - lastKept.getAtMillis() <= 0) {
                continue; // 乱序/同刻
            }
            if (p.getLat() == lastKept.getLat() && p.getLng() == lastKept.getLng()) {
                continue; // 重复点
            }
            WalkTrackPoint ref = anchor == null ? lastKept : anchor;
            long dtMs = p.getAtMillis() - ref.getAtMillis();
            if (dtMs <= 0) {
                continue;
            }
            double segment = haversineM(ref.getLat(), ref.getLng(), p.getLat(), p.getLng());
            if (segment / (dtMs / 1000.0) > MAX_SPEED_MPS) {
                continue; // 跳点：不计程、不推进锚点，也不进时间轴
            }
            kept.add(p);
            if (acc > MAX_ACCURACY_RELIABLE_M) {
                continue; // 低精度：保留时间轴（不丢整段），但不计里程、不当锚点
            }
            double gate = Math.max(MIN_SEGMENT_M, Math.max(acc, accuracyOf(ref)));
            if (segment >= gate) {
                distance += segment; // 位移必须大于两端定位误差才算真实移动
            }
            anchor = p;
        }
        if (kept.size() < 2) {
            return null;
        }
        WalkTrackPoint first = kept.get(0);
        WalkTrackPoint last = kept.get(kept.size() - 1);
        long sec = (last.getAtMillis() - first.getAtMillis()) / 1000L;
        int duration = (int) Math.max(0L, sec - Math.max(0, pausedSec));
        return new Cleaned(List.copyOf(kept), (int) Math.round(distance), duration);
    }

    /** 精度取"有限正数"为已知；0/负数/NaN/无穷一律视为未知（历史数据/设备不支持该字段）。 */
    static double accuracyOf(WalkTrackPoint p) {
        double a = p == null ? 0 : p.getAccuracyM();
        return Double.isFinite(a) && a > 0 ? a : 0;
    }

    static boolean validCoord(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= -90.0 && lat <= 90.0
                && lng >= -180.0 && lng <= 180.0;
    }

    /** Haversine 球面距离（米）。坐标已由调用方校验过范围。 */
    public static double haversineM(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /**
     * 抽稀成 [lat,lng] 序列：首尾必留，其余等步长取样（≤ max 个）。只服务"画示意路线"，
     * 不参与任何里程计算——所以抽稀不会改变已持久化的 distance_m。
     */
    public static List<double[]> polyline(List<WalkTrackPoint> points, int max) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        int cap = Math.max(2, max);
        List<double[]> out = new ArrayList<>();
        if (points.size() <= cap) {
            for (WalkTrackPoint p : points) {
                out.add(new double[]{p.getLat(), p.getLng()});
            }
            return out;
        }
        int stride = (int) Math.ceil((points.size() - 1) / (double) (cap - 1));
        for (int i = 0; i < points.size(); i += stride) {
            WalkTrackPoint p = points.get(i);
            out.add(new double[]{p.getLat(), p.getLng()});
        }
        WalkTrackPoint last = points.get(points.size() - 1);
        double[] tail = new double[]{last.getLat(), last.getLng()};
        double[] prev = out.get(out.size() - 1);
        if (prev[0] != tail[0] || prev[1] != tail[1]) {
            out.add(tail);
        }
        return out;
    }
}
