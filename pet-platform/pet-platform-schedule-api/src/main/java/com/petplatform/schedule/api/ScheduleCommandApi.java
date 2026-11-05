package com.petplatform.schedule.api;

import com.petplatform.schedule.api.dto.BatchGenerateCommand;
import com.petplatform.schedule.api.dto.BatchGenerateResult;
import com.petplatform.schedule.api.dto.ScheduleBrief;

import java.time.LocalTime;

/**
 * 排班写侧（ADR-034，P8 §4.3 :221）。表 Owner 仍是 schedule，但**控制器在 app 层**——
 * 改/删一条班之前必须问 appointment"这天还有没有进行中的预约"，而依赖矩阵 §3.2 禁止
 * schedule → appointment，只有 app 能同时读到两边（先例：ADR-024 §3、ADR-033）。
 *
 * <p>本接口不含预约预检，调用方（app 控制器）必须自己先 {@link #findInStore} 再决定要不要写。
 */
public interface ScheduleCommandApi {

    /** 区间最长天数（与读侧 {@code StoreScheduleController.MAX_SPAN_DAYS} 同口径）。 */
    int MAX_SPAN_DAYS = 31;

    /** 单次写入行数上限（与 {@code ScheduleQueryMapper} 的 LIMIT 500 呼应）。 */
    int MAX_ROWS = 500;

    /** 本店的一条排班；不存在或属别店返回 null（调用方按 10004 处理，不泄露存在性）。 */
    ScheduleBrief findInStore(Long storeId, Long scheduleId);

    /**
     * 批量建 {@code WORKING} 班。撞 {@code uk_emp_date} 的日子**不触碰、进回执 skipped**：
     * 覆盖会跨店吞掉别人排的班，整批回滚会让"月度重排 + 补几天"因一天冲突而全月失败。
     *
     * @param storeId 授权主语（X-Store-Id），不是入参
     */
    BatchGenerateResult batchGenerate(Long storeId, BatchGenerateCommand cmd);

    /** 只改时段（{@code status} 不在射程内，请假属 P8 :222 那一片）。0 行影响返回 null。 */
    ScheduleBrief updateTime(Long storeId, Long scheduleId, LocalTime startTime, LocalTime endTime);

    /** 物理删（表无 deleted 列，P2 约定）。返回受影响行数，0=不存在或属别店。 */
    int delete(Long storeId, Long scheduleId);
}
