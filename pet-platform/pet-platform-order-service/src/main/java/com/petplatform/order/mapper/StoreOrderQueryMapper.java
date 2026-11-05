package com.petplatform.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.order.domain.OrderEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * B 端订单查询专用 mapper（ADR-019 §2：全新文件，避开并行会话高频改动的 OrderMapper/OrderStatsMapper）。
 * 同一实体挂两个 mapper 在 MyBatis-Plus 下合法；过滤条件全走 Wrappers，不写 SQL。
 */
@Mapper
public interface StoreOrderQueryMapper extends BaseMapper<OrderEntity> {
}
