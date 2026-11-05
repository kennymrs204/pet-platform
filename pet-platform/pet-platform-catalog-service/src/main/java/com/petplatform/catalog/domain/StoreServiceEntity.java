package com.petplatform.catalog.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.petplatform.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 门店上架服务（cat_store_service）：关系实体一等公民——价格/时长/资源/取消政策
 * 只存在于这一层（第七阶段 §cat_store_service）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cat_store_service")
public class StoreServiceEntity extends BaseEntity {

    private Long storeId;
    private Long serviceId;
    /** 分（ADR-003-14） */
    private Long price;
    /** 分钟，须为 30 的倍数（ADR-005-2） */
    private Integer durationMin;
    /** 空=无需资源；PUT 是全量提交，故 null 要真的写回 NULL。 */
    @TableField(value = "required_resource_type", updateStrategy = FieldStrategy.ALWAYS)
    private String requiredResourceType;

    /**
     * NULL=继承平台服务默认；required_resource_type 同理（空=无需资源）。
     * updateStrategy=ALWAYS：PUT 是全量提交，把字段改回 null（取消覆盖/取消资源要求）
     * 是合法操作，默认 NOT_NULL 策略会静默跳过 null 列。
     */
    @TableField(value = "allow_user_pick_employee", updateStrategy = FieldStrategy.ALWAYS)
    private Boolean allowUserPickEmployee;

    private Integer cancelFreeHours;
    private Integer cancelPenaltyRate;
    /** ON/OFF */
    private String status;
}
