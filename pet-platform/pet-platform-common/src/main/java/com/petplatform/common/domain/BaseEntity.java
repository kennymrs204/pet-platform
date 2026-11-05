package com.petplatform.common.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通用实体基类（第七阶段 §1 全局规范）。
 *
 * <p>逻辑删除统一采用 P1 方案（对 ADR-007 的物理细节统一，见第七阶段文档修订注记）：
 * 全库 deleted 均为 BIGINT，删除时置为自身 id（delval="id"），
 * 使"删除后重建"不撞唯一键；未删除行恒为 0。
 *
 * <p>created_at/updated_at 由数据库 DEFAULT/ON UPDATE 维护，
 * MyBatis-Plus 默认 NOT_NULL 插入策略会省略 null 字段，无需字段填充器。
 *
 * <p>version（乐观锁）仅状态机聚合根实体声明，不放在基类。
 *
 * <p>P2/P3 表（sch_schedule、sch_resource_schedule、append-only 表）不继承本类。
 */
@Data
public abstract class BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic(value = "0", delval = "id")
    private Long deleted;
}
