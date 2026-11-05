package com.petplatform.fulfillment.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 按预约回显的履约记录（ADR-038 洗护看板）。
 *
 * <p>字段取自 {@code ful_bath_record}（V1:431-451），刻意**不带 photos 明细**：
 * 看板只需要"这单做完了没、谁做的、什么时候"，要看照片走单预约的记录视图；
 * 逐条 presign 会把一次队列查询放大成 M 次签名（同 ADR-032 对护理动态的取舍）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceRecordBrief {

    private Long appointmentId;
    /** 实际执行人（emp_employee.id） */
    private Long employeeId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** 实际执行项目（JSON 数组原样解析后的 code 列表；脏数据降级空列表） */
    private java.util.List<String> serviceItems;
    private String remark;
    /** 照片张数（key 数组长度；不出 key 也不出 URL） */
    private Integer photoCount;
    /** 是否店长顶班补录（ADR-001-G 未拍板，本期恒 0；出参保留是为了前端不再改一次契约） */
    private Boolean byManager;
}
