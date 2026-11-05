package com.petplatform.boarding.web;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 托管状态参数的唯一解析口（V3 抽出）。
 *
 * <p>三个读侧出口（在住列表、历史分页、分页/计数）必须认同一套状态字面量：
 * 各写一份 split/校验，早晚变成「这个端点收 ABNORMAL、那个端点报 10001」。
 * 非法即拒（不静默忽略未知状态），与 {@code BoardingQueryService#history} 的钳制口径一致。
 */
final class BoardingStatuses {

    private BoardingStatuses() {
    }

    /**
     * @param allowed         本端点接受的状态集合
     * @param fallback        参数缺省时使用的集合（各端点缺省语义不同，故由调用方给）
     * @param illegalMessage  非法时的文案（保持各端点既有提示不变）
     */
    static Set<String> parse(String raw, Set<String> allowed, Set<String> fallback,
                             Function<String, String> illegalMessage) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Set<String> set = Arrays.stream(raw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));
        if (set.isEmpty() || !allowed.containsAll(set)) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, illegalMessage.apply(raw));
        }
        return set;
    }
}
