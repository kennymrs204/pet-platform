package com.petplatform.pet.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 健康档案变更日志（pet_health_change_log，append-only）。
 * 不继承 BaseEntity：没有 updated_at/deleted，日志行不更新、不逻辑删除。
 * before/after/changed_fields 为 JSON 列的字符串形态，由 service 层 Jackson 往返。
 */
@Data
@TableName("pet_health_change_log")
public class PetHealthChangeLogEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long petId;
    /** B 端门店上下文；C 端自助修改为空 */
    private Long storeId;
    /** CUSTOMER/EMPLOYEE/ADMIN */
    private String operatorType;
    private Long operatorId;
    private String operatorName;
    private String operatorRole;
    private String changedFields;
    private String beforeJson;
    private String afterJson;
    private LocalDateTime createdAt;
}
