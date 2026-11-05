package com.petplatform.boarding.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.petplatform.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 每日护理记录（boa_daily_care_record，append-only，ADR-015 §5）。
 * feeding/watering/walk/photos 为 MySQL JSON 列的字符串形态（service 层受控 Jackson 往返）。
 * caretaker_id = 实际写入人（主责或代录员工；主责身份在 boa_boarding_stay.caretaker_id，ADR-015 §5 补记 2026-10-27）。
 * 每天多条为既定设计——无 (stay,date) 唯一约束。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("boa_daily_care_record")
public class DailyCareRecordEntity extends BaseEntity {

    private Long boardingStayId;
    private LocalDate careDate;
    private Long caretakerId;
    private String feeding;
    private String watering;
    private String walk;
    private String observation;
    private String photos;
    private Boolean abnormalFlag;
    private String abnormalDesc;
}
