package com.petplatform.schedule.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 批量排班回执（ADR-034 §2）。
 *
 * <p>{@code skipped} 里带 {@code storeId} 是有意的：唯一键是全局的，"这人被别的店占了"这件事
 * 在系统里只有这一瞬间能被店长看到——`ScheduleDayView`（读侧）根本不导出 storeId，事后查询是盲的。
 */
@Data
@Builder
public class BatchGenerateResult {

    /** 本次真正新建出来的班（含 id，供前端直接渲染）。 */
    private List<ScheduleBrief> created;

    /** 因当日已有班而未被触碰的日子。 */
    private List<Skipped> skipped;

    @Data
    @Builder
    public static class Skipped {
        private Long employeeId;
        private LocalDate workDate;
        /** 已有那条码落在哪家店——不是本店的场合，店长要知道得去那家店调，而不是在本店重试。 */
        private Long storeId;
    }
}
