package com.petplatform.store.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 门店资料全视图（ADR-020，B 端设置页数据源；比 ADR-016 的 StoreView 多联系方式/公告）。
 * 金额/ID 类字段经全局 Jackson 字符串化；经纬度是 DECIMAL → BigDecimal 同样字符串化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreInfoView {

    private Long id;
    private String name;
    private String phone;
    private String address;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private LocalTime businessStartTime;
    private LocalTime businessEndTime;
    private LocalTime breakStartTime;
    private LocalTime breakEndTime;
    /** OPEN/SUSPENDED/CLOSED —— 只读：停业属平台级运营动作，不经门店设置修改 */
    private String status;
    /** NORMAL/PLATFORM_HQ —— 只读：可写即提权面（ADR-020 §3） */
    private String storeType;
    private String announcement;
}
