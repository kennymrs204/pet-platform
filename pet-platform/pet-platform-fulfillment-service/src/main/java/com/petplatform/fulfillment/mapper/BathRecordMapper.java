package com.petplatform.fulfillment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.fulfillment.domain.BathRecordEntity;
import org.apache.ibatis.annotations.Mapper;

/** 洗澡/美容记录写入（ADR-048）。读侧走 {@link ServiceRecordQueryMapper} 的自写 SQL，不混用。 */
@Mapper
public interface BathRecordMapper extends BaseMapper<BathRecordEntity> {
}
