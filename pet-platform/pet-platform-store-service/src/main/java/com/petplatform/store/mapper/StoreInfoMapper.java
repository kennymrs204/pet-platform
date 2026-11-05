package com.petplatform.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.store.api.dto.StoreInfoUpdate;
import com.petplatform.store.domain.StoreEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 门店资料读写 mapper（ADR-020，新文件；不碰 ADR-016 的 StoreQueryMapper）。
 * 更新走显式列名 SQL 而非 updateById：MP 默认 NOT_NULL 策略会把「清空午休/清空公告」
 * 静默吞掉（写不回 NULL），这里必须能真的置空。
 */
@Mapper
public interface StoreInfoMapper extends BaseMapper<StoreEntity> {

    @Update("""
            UPDATE sto_store SET
              name = #{c.name}, phone = #{c.phone}, address = #{c.address},
              latitude = #{c.latitude}, longitude = #{c.longitude},
              business_start_time = #{c.businessStartTime}, business_end_time = #{c.businessEndTime},
              break_start_time = #{c.breakStartTime}, break_end_time = #{c.breakEndTime},
              announcement = #{c.announcement}
            WHERE id = #{storeId} AND deleted = 0
            """)
    int updateInfo(@Param("storeId") Long storeId, @Param("c") StoreInfoUpdate cmd);
}
