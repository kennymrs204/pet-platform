package com.petplatform.fulfillment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.fulfillment.api.dto.WalkTrackPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 轨迹点的 JSON 编解码（写侧与读侧共用同一份数组形状，避免两份形状定义漂移）。
 *
 * <p>形状固定为 {@code [[lat,lng,atMillis,accuracyM],...]}（数组比对象省一半字节，2000 点也不至于撑爆 JSON 列）。
 * **读取兼容 3 元组**（本轮新增精度之前保存的轨迹）：缺第 4 位按"精度未知(0)"处理，
 * 保证历史轨迹照常渲染，不需要迁移重写。
 * 读侧遇到脏值只降级为空列表并告警，不让一条坏轨迹把整块看板打成 500。
 */
@Slf4j
@Component
public class WalkTrackCodec {

    private final ObjectMapper json;

    public WalkTrackCodec(ObjectMapper json) {
        this.json = json;
    }

    public String write(List<WalkTrackPoint> points) {
        List<double[]> rows = new ArrayList<>(points.size());
        for (WalkTrackPoint p : points) {
            rows.add(new double[]{p.getLat(), p.getLng(), p.getAtMillis(), p.getAccuracyM()});
        }
        try {
            return json.writeValueAsString(rows);
        } catch (Exception e) {
            throw new IllegalStateException("轨迹点序列化失败", e);
        }
    }

    public List<WalkTrackPoint> read(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<List<Number>> rows = json.readValue(raw, new TypeReference<List<List<Number>>>() {
            });
            List<WalkTrackPoint> out = new ArrayList<>();
            for (List<Number> row : rows) {
                if (row == null || row.size() < 3 || row.get(0) == null || row.get(1) == null || row.get(2) == null) {
                    continue;
                }
                double accuracy = row.size() >= 4 && row.get(3) != null ? row.get(3).doubleValue() : 0;
                out.add(new WalkTrackPoint(row.get(0).doubleValue(), row.get(1).doubleValue(),
                        row.get(2).longValue(), accuracy));
            }
            return out;
        } catch (Exception e) {
            log.warn("轨迹点 JSON 解析失败，按空轨迹处理: {}", e.getMessage());
            return List.of();
        }
    }
}
