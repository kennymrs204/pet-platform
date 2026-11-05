package com.petplatform.fulfillment.api.dto;

import lombok.Data;

import java.util.List;

/**
 * "完成服务"请求体（ADR-048）——两个字段都可空，因为两张表的对应列都可空。
 *
 * <p>{@code serviceItems} 只有洗护记录有这一列，遛狗单传了会被忽略（不静默改写成别的列）。
 * {@code photos} 本轮<b>不开放</b>：门店 UI 里还没有任务级照片上传入口，
 * 与其开一个"传了也没地方显示"的字段，不如等上传链接上再加。
 */
@Data
public class FinishServiceRequest {

    /** 执行项（洗澡页那组勾选框），如 ["BATH","EAR_CLEAN"]；null=不写该列，空数组=明确"一项都没勾" */
    private List<String> serviceItems;

    /** 备注，≤500 字（超长由写侧截断） */
    private String remark;
}
