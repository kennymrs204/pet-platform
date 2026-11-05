package com.petplatform.order.api;

import com.petplatform.common.result.PageResult;
import com.petplatform.order.api.dto.StoreOrderView;

import java.time.LocalDate;

/**
 * 门店订单只读查询（ADR-019；表 Owner=order）。
 * WHERE store_id 由本模块强制，调用方只能传自己上下文里的门店；跨店命中返回 null，由 web 层按 10004 处理。
 */
public interface StoreOrderQueryApi {

    /**
     * 本店订单分页。
     *
     * @param status    空=全部；单值枚举（非法值由 web 层先拦 10001）
     * @param dateFrom  含当日起点（created_at &gt;= from 00:00）
     * @param dateTo    含当日终点（created_at &lt; to+1 00:00）
     * @param keyword   订单号前缀（{@code order_no LIKE 'kw%'}）；空=不过滤；&lt;2 字符由 web 层先拦 10001
     */
    PageResult<StoreOrderView> pageInStore(Long storeId, String status, LocalDate dateFrom, LocalDate dateTo,
                                          String keyword, int pageNum, int pageSize);

    /**
     * 本店某客户的订单分页（客户 360 订单史）。WHERE store_id+user_id 双条件；
     * 排序与 {@link #pageInStore} 一致（created_at desc → id desc）。
     * 客户是否属于本店客户集由 web 层按 customer 模块口径校验（10004 防枚举），本方法只按数据条件返回。
     */
    PageResult<StoreOrderView> pageInStoreByUser(Long storeId, Long userId, int pageNum, int pageSize);

    /** 本店订单详情；不存在、已删或他店订单返回 null。 */
    StoreOrderView detailInStore(Long storeId, Long orderId);
}
