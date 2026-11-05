package com.petplatform.boarding.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 全店「今日护理动态」行（B 端托管页右栏，ADR-032）。
 *
 * <p>刻意**不含 photos/photoUrls**：动态卡只列文本；逐条 presign 会把一次查询放大成 M 次签名
 * （MinIO SDK 每次签名前要真实探测桶 region），要看照片走单住单的 {@code /{id}/care-records}。
 *
 * <p>{@code petName/caretakerName} 由装配层经 pet-api / employee-api 批量回填，
 * 不在此 SQL 里 join 他模块表（跨模块 JOIN 等于绕过表 Owner）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CareActivityView {

    private Long recordId;
    private Long stayId;
    private Long petId;
    /** 未回填（宠物已删/无权）时为 null，前端回落占位 */
    private String petName;
    private LocalDate careDate;
    /** 记录填写人＝实际写入者（该单主责，或持 boarding:care:create 的代录员工；ADR-015 §5 补记 2026-10-27） */
    private Long caretakerId;
    private String caretakerName;
    private CareRecordRequest.Feeding feeding;
    private CareRecordRequest.Watering watering;
    private CareRecordRequest.Walk walk;
    private String observation;
    private Boolean abnormalFlag;
    private String abnormalDesc;
}
