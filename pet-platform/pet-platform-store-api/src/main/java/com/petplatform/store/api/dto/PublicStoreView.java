package com.petplatform.store.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公开浏览门店视图（store 第二段，ADR-022，GET /public/stores 数据块）。
 * 只含 C 端主页卡片字段；不含营业时间/坐标等内部运营字段（最小披露）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicStoreView {

    private Long id;
    private String name;
    private String address;
    private String phone;
    /** 列表口径固定 OPEN（ADR-022 §2：SUSPENDED/CLOSED 不对 C 端展示） */
    private String status;
}
