package com.petplatform.boarding.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.petplatform.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 托管入住单（boa_boarding_stay，聚合根，ADR-015 §1）。
 * version 不走 MP OptimisticLocker 插件（工程未注册），状态写操作全部经
 * BoardingStayMapper 的"状态+version 条件更新"SQL，禁止先查再写。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("boa_boarding_stay")
public class BoardingStayEntity extends BaseEntity {

    private Long appointmentId;
    private Long petId;
    private Long storeId;
    private LocalDate checkinDate;
    private LocalDate checkoutDate;
    private LocalDateTime actualCheckinAt;
    private LocalDateTime actualCheckoutAt;
    @TableField("kennel_resource_id")
    private Long kennelResourceId;
    /** EXPECTED/CHECKED_IN/DAILY_CARE/CHECKED_OUT/ABNORMAL */
    private String status;
    private Long caretakerId;
    private String specialInstructions;
    private Integer version;
}
