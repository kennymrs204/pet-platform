package com.petplatform.app.service;

import com.petplatform.app.mapper.PlatformParamMapper;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 平台参数只读查询（ADR-020 §2）：给 B 端设置页展示"规则从哪来"，并如实标出**哪些参数当前没有任何代码消费**。
 * 没有 consumed 标记，店长会以为改了这个数门店行为就会变——而写侧端点（/admin/configs）至今未实现，误解成本更高。
 */
@Slf4j
@Service
public class PlatformParamQuery {

    /**
     * 全仓 grep 得到的真实消费方：appointment（501~506 经 AppointmentConfigMapper）、pet（508 经 SizeCategoryResolver）、
     * order（507 经本类 {@link #longValue} 供退款审批限额判定，ADR-037 §4）。
     */
    private static final Set<String> CONSUMED = Set.of(
            "appointment.max_advance_days",
            "appointment.min_lead_hours",
            "appointment.pay_timeout_minutes",
            "appointment.employee_buffer_minutes",
            "order.unpaid_limit_per_user",
            "order.daily_create_limit_per_user_store",
            "refund.auto_approve_limit_fen",
            "refund.auto_approve_minutes",
            "size_category.weight_thresholds");

    private final PlatformParamMapper platformParamMapper;

    public PlatformParamQuery(PlatformParamMapper platformParamMapper) {
        this.platformParamMapper = platformParamMapper;
    }

    @Transactional(readOnly = true)
    public List<PlatformParam> list() {
        return platformParamMapper.selectAll().stream()
                .map(r -> PlatformParam.builder()
                        .key(r.getConfigKey())
                        .value(r.getConfigValue())
                        .remark(r.getRemark())
                        .consumed(CONSUMED.contains(r.getConfigKey()))
                        .build())
                .toList();
    }

    /**
     * 运行期取一个数值型参数（退款审批限额等判定用）。
     * key 缺失或值非数字时回 {@code fallback} 并 warn——配置写坏不该让审批链整体 500。
     */
    @Transactional(readOnly = true)
    public long longValue(String key, long fallback) {
        String raw = platformParamMapper.selectValue(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("平台参数 {} 值非数字（{}），按兜底值 {} 处理", key, raw, fallback);
            return fallback;
        }
    }

    /** 平台参数（只读；value 为字符串，508 那项本身是 JSON 串）。 */
    @Data
    @Builder
    public static class PlatformParam {
        private String key;
        private String value;
        private String remark;
        /** false = 当前无 Java 读取该 key，展示值不生效 */
        private boolean consumed;
    }
}
