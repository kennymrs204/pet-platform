import { http } from './client'
import type { PageResult } from './catalog'

/**
 * C 端订单（ADR-016 §5，后端 CustomerOrderController）。
 * 关键契约事实（决定页面结构）：
 *  - `?status=` 是**单值精确匹配**，不是逗号多值 → 一个页签要覆盖多个状态时前端并发多次请求再合并；
 *  - OrderView **不含 petId / 门店名**，itemSnapshot 只有 serviceName/durationMin/price；
 *    宠物与门店信息必须由预约列表（含 petId）与演示门店数据在本地 join；
 *  - AppointmentSelfView 也不含 orderId，两域靠 `order.appointmentId` 反向对齐（ord_order 有 uk_appointment）。
 * 状态枚举出自 V1 DDL：WAIT_PAY/PAID/WAIT_SERVICE/SERVING/COMPLETED/CANCELLED/REFUNDING/REFUNDED。
 */
export type OrderStatus =
  | 'WAIT_PAY'
  | 'PAID'
  | 'WAIT_SERVICE'
  | 'SERVING'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REFUNDING'
  | 'REFUNDED'

export interface OrderView {
  id: string
  orderNo: string
  appointmentId: string
  userId: string
  storeId: string
  amount: string | number
  status: OrderStatus
  payExpireAt: string | null
  paidAt: string | null
  paymentMethod: string | null
  itemSnapshot: { serviceName?: string; durationMin?: number; price?: string | number; days?: number } | null
  createdAt: string
}

export const ORDER_STATUS_TEXT: Record<OrderStatus, string> = {
  WAIT_PAY: '待支付',
  PAID: '已支付',
  WAIT_SERVICE: '待服务',
  SERVING: '服务中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  REFUNDING: '退款处理中',
  REFUNDED: '已退款',
}

export const orderApi = {
  list: (status?: OrderStatus, pageSize = 100) => {
    const q = new URLSearchParams({ pageNum: '1', pageSize: String(pageSize) })
    if (status) q.set('status', status)
    return http.get<PageResult<OrderView>>(`/customer/orders?${q.toString()}`, { auth: true })
  },
  detail: (id: string) => http.get<OrderView>(`/customer/orders/${id}`, { auth: true }),
  /** 重新拉起支付；非 WAIT_PAY → 60001。返回 paymentNo + 通道参数。 */
  pay: (id: string) =>
    http.post<{ paymentNo: string; params: Record<string, string> | null }>(`/customer/orders/${id}/pay`, undefined, {
      auth: true,
    }),
}
