package com.petplatform.fulfillment.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.petplatform.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 洗澡/美容履约记录（ful_bath_record，append-only）。
 *
 * <p>serviceItems/photos 是 MySQL JSON 列的字符串形态，由 service 层用 Jackson 受控往返
 * （读侧同 {@code ServiceRecordQueryService} 的口径）。脏 JSON 会让整批写入被 3140 拒收，
 * 所以只在拿到合法列表时才序列化。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ful_bath_record")
public class BathRecordEntity extends BaseEntity {

    private Long appointmentId;
    private Long petId;
    private Long employeeId;
    private Long storeServiceId;
    /** 执行人当时角色（DDL 注释原话），如 GROOMER */
    private String executorRole;
    /** 店长顶班/代录（ADR-001-G） */
    private Boolean byManager;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String serviceItems;
    private String photos;
    private String remark;
}
