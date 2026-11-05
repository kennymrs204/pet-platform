package com.petplatform.store.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.petplatform.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalTime;

/** 门店（sto_store）。ADR-020 设置页读写；与 StoreQueryMapper 的纯读投影互不影响。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sto_store")
public class StoreEntity extends BaseEntity {

    private String name;
    private String phone;
    private String address;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private LocalTime businessStartTime;
    private LocalTime businessEndTime;
    private LocalTime breakStartTime;
    private LocalTime breakEndTime;
    /** OPEN/SUSPENDED/CLOSED */
    private String status;
    /** NORMAL/PLATFORM_HQ */
    private String storeType;
    private String announcement;
}
