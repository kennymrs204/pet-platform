package com.petplatform.pet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.pet.mapper.SysConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 体重→笼位尺寸分类（ADR-014：SMALL/MEDIUM/LARGE，托管笼位分配依据，客户端不可写）。
 * 阈值优先读 sys_config['size_category.weight_thresholds']（V2 种子 508，JSON：
 * {"smallMaxKg":10,"mediumMaxKg":25}），读取失败回落同值默认并 WARN——绝不因配置问题阻断建档。
 * 边界语义：w ≤ smallMax → SMALL；smallMax &lt; w ≤ mediumMax → MEDIUM；w &gt; mediumMax → LARGE。
 */
@Slf4j
@Component
public class SizeCategoryResolver {

    static final String CONFIG_KEY = "size_category.weight_thresholds";
    static final BigDecimal DEFAULT_SMALL_MAX = new BigDecimal("10");
    static final BigDecimal DEFAULT_MEDIUM_MAX = new BigDecimal("25");

    private final SysConfigMapper sysConfigMapper;
    private final ObjectMapper json;

    public SizeCategoryResolver(SysConfigMapper sysConfigMapper, ObjectMapper json) {
        this.sysConfigMapper = sysConfigMapper;
        this.json = json;
    }

    /** 体重为空 → 尺寸分类为空（未称重不猜测）。 */
    public String resolve(BigDecimal weightKg) {
        if (weightKg == null) {
            return null;
        }
        Thresholds t = thresholds();
        if (weightKg.compareTo(t.smallMaxKg()) <= 0) {
            return "SMALL";
        }
        return weightKg.compareTo(t.mediumMaxKg()) <= 0 ? "MEDIUM" : "LARGE";
    }

    private Thresholds thresholds() {
        try {
            String raw = sysConfigMapper.valueOf(CONFIG_KEY);
            if (raw == null || raw.isBlank()) {
                return Thresholds.DEFAULT;
            }
            JsonNode node = json.readTree(raw);
            JsonNode small = node.path("smallMaxKg");
            JsonNode medium = node.path("mediumMaxKg");
            if (!small.isNumber() || !medium.isNumber()) {
                log.warn("sys_config {} 缺 smallMaxKg/mediumMaxKg，回落默认", CONFIG_KEY);
                return Thresholds.DEFAULT;
            }
            return new Thresholds(small.decimalValue(), medium.decimalValue());
        } catch (Exception e) {
            log.warn("读取 {} 失败，回落默认阈值：{}", CONFIG_KEY, e.getMessage());
            return Thresholds.DEFAULT;
        }
    }

    record Thresholds(BigDecimal smallMaxKg, BigDecimal mediumMaxKg) {
        static final Thresholds DEFAULT = new Thresholds(DEFAULT_SMALL_MAX, DEFAULT_MEDIUM_MAX);
    }
}
