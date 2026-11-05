package com.petplatform.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.schedule.domain.ResourceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 资源台账 mapper（ADR-020，新文件；与 ResourceOccupancyMapper 的占用读写互不影响）。 */
@Mapper
public interface StoreResourceMapper extends BaseMapper<ResourceEntity> {

    /** 停用决策的锁定读：锁住资源行直到事务结束，避免与分配路径的状态复检交错。 */
    @Select("SELECT id, store_id, type, name, status, created_at, updated_at, deleted"
            + " FROM sch_resource WHERE id = #{id} AND store_id = #{storeId} AND deleted = 0 FOR UPDATE")
    ResourceEntity selectForUpdate(@Param("storeId") Long storeId, @Param("id") Long id);
}
