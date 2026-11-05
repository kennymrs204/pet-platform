package com.petplatform.order.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/** 创建订单命令（appointment 创建事务内调用，ADR-016 §6；金额=price_snapshot 分）。 */
@Data
@Builder
public class CreateOrderCommand {

    private Long appointmentId;
    private Long userId;
    private Long storeId;
    private Long amount;
    private LocalDateTime payExpireAt;
    /** 服务名/时长/单价快照（落 item_snapshot JSON，目录后续变更不影响历史单） */
    private Map<String, Object> itemSnapshot;
}
