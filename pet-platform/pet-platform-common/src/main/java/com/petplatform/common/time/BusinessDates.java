package com.petplatform.common.time;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 业务"今日"统一口径（ADR-013）：经营统计的日期窗口一律按 Asia/Shanghai 计算，
 * 与 MySQL（default-time-zone=+08:00）及 JDBC（serverTimezone=Asia/Shanghai）对齐；
 * 容器 JVM 默认区是 UTC，禁止裸用 LocalDate.now()/LocalDateTime.now() 推"今日"。
 */
public final class BusinessDates {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private BusinessDates() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** [date 00:00, date+1 00:00) 左闭右开，DATETIME 范围过滤用 */
    public static LocalDateTime dayStart(LocalDate date) {
        return date.atStartOfDay();
    }

    public static LocalDateTime dayEndExclusive(LocalDate date) {
        return date.plusDays(1).atStartOfDay();
    }
}
