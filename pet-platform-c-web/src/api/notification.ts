import { http } from './client'

/**
 * C 端消息中心（ADR-041）。数据源是 ntf_notification：退款链上的三条消息由后端事件桥写入，
 * 前端**只读**——绝不根据订单状态在本地"猜"该出现什么消息，消息以库里的行为准。
 *
 * 口径提醒：Long 经全局 Jackson 字符串化，所以 id/bizId/total/未读数都可能是字符串（同 api/order.ts 的处置）。
 */

export interface NotificationItem {
  id: string
  /** 客户可见类型：APPOINTMENT_CREATED（下单成功，ADR-045）、APPOINTMENT_PAID（支付成功，ADR-046）、APPOINTMENT_CANCELLED（未支付取消）+ 退款三类 REFUND_APPLIED/SUCCESS/REJECTED */
  type: string
  title: string
  content: string
  /** 跳转目标模块；null=这条消息不可跳转（biz_ref 缺失或畸形） */
  bizModule: string | null
  bizId: string | null
  read: boolean
  readAt: string | null
  createdAt: string
}

export interface NotificationPage {
  total: string | number
  pageNum: number
  pageSize: number
  list: NotificationItem[] | null
}

/** 小标签：只做"这类消息是关于什么的"，不建分类体系（下单/支付/取消/退款几类结果）。 */
export const NOTIFICATION_TYPE_TAG: Record<string, string> = {
  APPOINTMENT_CREATED: '下单',
  APPOINTMENT_PAID: '支付',
  APPOINTMENT_CANCELLED: '取消',
  APPOINTMENT_COMPLETED: '服务完成',
  BOARDING_CARE: '护理',
  REFUND_APPLIED: '退款进度',
  REFUND_SUCCESS: '退款结果',
  REFUND_REJECTED: '退款结果',
}

/**
 * 点击跳转：module 决定路由，bizId 是该路由的主键。
 *
 * `appointment` → `/orders/{bizId}`（现有 C 端订单详情，退款进度卡就在那页上）。
 * `boarding` → `/boarding/{bizId}`（护理记录更新提醒：顾客托管详情/日报；主人态读口，非主人 403）。
 * 其它 module（如发给员工的 `store_refund`，其 bizId 是退款单 id）**不跳**——
 * 拿 refundId 去拼订单详情路由会打开一条不相干的单，所以宁可不可点击也不猜关联。
 */
export function notificationLink(item: NotificationItem): string | null {
  if (item.bizModule === 'appointment' && item.bizId) return `/orders/${item.bizId}`
  if (item.bizModule === 'boarding' && item.bizId) return `/boarding/${item.bizId}`
  return null
}

export const notificationApi = {
  list: (pageNum = 1, pageSize = 20, signal?: AbortSignal) =>
    http.get<NotificationPage>(`/customer/notifications?pageNum=${pageNum}&pageSize=${pageSize}`, { auth: true, signal }),
  unreadCount: (signal?: AbortSignal) =>
    http.get<string | number>('/customer/notifications/unread-count', { auth: true, signal }),
  markRead: (id: string) => http.post<void>(`/customer/notifications/${id}/read`, undefined, { auth: true }),
  /** 返回本次实际变更条数（重复调用为 0） */
  markAllRead: () => http.post<string | number>('/customer/notifications/read-all', undefined, { auth: true }),
}
