package com.petplatform.fulfillment.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 按预约回显的遛狗记录（ADR-049 遛狗看板）。
 *
 * <p>字段取自 {@code ful_walk_record}（V1:453-475）。与洗护版 {@link ServiceRecordBrief} 的两处差异都由表的形状决定：
 * walk 表没有 {@code service_items} 列（完成遛狗时没有可勾的执行项目），故不出；
 * {@code duration_min} 是遛狗记录的核心事实（完成时由实起止差实算，NOT NULL），故必出。
 * {@code distance_m/track_summary/poop_count} 三列全链路仍无写入入口（ADR-048 刻意留 NULL，等产品决策），
 * 一并不出——出了只会让前端对着恒空的字段编 UI。
 * <b>{@code photos} 自 2026-10-24 起有写入入口</b>（完成后的补照片操作），出参是对象存储 key 列表；
 * 可读 URL 由装配层（app）按 ADR-029 现签，本 DTO 不携带任何临时 URL。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalkRecordBrief {

    private Long appointmentId;
    /** 实际执行人（emp_employee.id） */
    private Long employeeId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** 实际遛狗时长（分钟） */
    private Integer durationMin;
    private String remark;
    /** 是否店长顶班代点（点的人 ≠ 执行人） */
    private Boolean byManager;
    /** 照片对象 key（{@code u-{uid}/WALK_PHOTO/…}）；无照片=空列表，绝不返回 null */
    private List<String> photos;
}
