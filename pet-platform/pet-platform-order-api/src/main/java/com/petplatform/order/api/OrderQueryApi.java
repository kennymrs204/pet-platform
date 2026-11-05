package com.petplatform.order.api;

import com.petplatform.common.result.PageResult;
import com.petplatform.order.api.dto.OrderView;

/** 订单只读查询（表 Owner：order，ADR-016）。 */
public interface OrderQueryApi {

    OrderView get(Long orderId);

    OrderView byAppointment(Long appointmentId);

    /** 指定用户订单分页（status 空=全部；created_at desc→id desc）。 */
    PageResult<OrderView> pageForUser(Long userId, String status, int pageNum, int pageSize);

    /** 未支付订单数（C8 防恶意占位，sys_config order.unpaid_limit_per_user）。 */
    long countUnpaid(Long userId);
}
