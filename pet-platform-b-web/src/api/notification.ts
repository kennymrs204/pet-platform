import { http } from './client'

/**
 * B 端消息中心（ADR-047）。数据源同样是 `ntf_notification`，但读的是**门店收件箱**：
 * 后端 `GET /api/v1/store/notifications*` 按「收件人=token 里的我」+「门店=token 里的 X-Store-Id」
 * 两个过滤取数，两者都不在本文件的任何请求参数里——所以前端既读不到别人的消息，
 * 也没法靠改参数去看别店的收件箱（门店头由 api/client.ts 统一注入）。
 *
 * 口径提醒：Long 经全局 Jackson 字符串化，id/bizId/total/未读数都可能是字符串。
 */

export interface NotificationItem {
  id: string
  /** 门店可见类型：REFUND_APPLIED（退款待处理）/ APPOINTMENT_TO_SERVE（新预约）/ CARE_ABNORMAL（照护异常）/ CHAT_MESSAGE（客户消息，H5） */
  type: string
  title: string
  content: string
  /** 跳转目标模块；null 或白名单外＝这条只读不可跳 */
  bizModule: string | null
  bizId: string | null
  /** H5：chat 提醒的深链定位键（非 chat 恒 null）——bizId 是"未读段首条消息 id"，不兼作会话 id */
  conversationId: string | null
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

/** 小标签：只说"这类消息是关于什么的"，不建分类体系（一期三类 + H5 客户消息 + 执行人指派/改派）。 */
export const NOTIFICATION_TYPE_TAG: Record<string, string> = {
  REFUND_APPLIED: '退款审核',
  APPOINTMENT_TO_SERVE: '新预约',
  APPOINTMENT_ASSIGNED: '已指派',
  APPOINTMENT_REASSIGNED: '已改派',
  CARE_ABNORMAL: '照护异常',
  CHAT_MESSAGE: '客户消息',
}

/**
 * 点击跳转：`module` 决定路由，`bizId` 是那条路由能认的键——**认不出就不跳**（ADR-041 §4 的纪律）。
 *
 * - `store_order`（bizId=订单 id）→ `/orders?openId=`：B 端订单页的展开键就是 `OrderItem.id`（实测）。
 * - `store_stay`（bizId=住单 id）→ `/boarding?openId=`：托管页同样按住单 id 展开（实测）。
 * - `store_refund`（bizId=退款单 id）→ `/orders?tab=REFUNDING`：待处理本质是**队列**，
 *   而订单页按 orderId 展开、退款单 id 拼不进那个键；跳筛选后的列表比跳一条打不开的行诚实。
 * - 其余（`appointment`=C 端个人消息、迁移前 legacy 的 `refund`）→ null：只读，不绑点击。
 */
export function notificationLink(item: NotificationItem): string | null {
  if (item.bizModule === 'store_order' && item.bizId) return `/orders?openId=${item.bizId}`
  if (item.bizModule === 'store_stay' && item.bizId) return `/boarding?openId=${item.bizId}`
  if (item.bizModule === 'store_refund') return '/orders?tab=REFUNDING'
  // 执行人"来活了"（ADR-052）：任务页按日期筛选，bizId 是预约 id 拼不进键 → 携 openId 跳转，
  // TasksPage 按日历前向搜索定位那一单并自动展开
  if (item.bizModule === 'store_task' && item.bizId) return `/tasks?openId=${item.bizId}`
  // H5 客户消息：深链键是 conversationId（bizId 是"未读段首条消息 id"，不是会话 id——
  // 拿 bizId 当 open 参数会定位到不存在的会话）。缺 conversationId 则本轮旧数据不跳。
  if (item.bizModule === 'chat' && item.conversationId) return `/customer-chat?open=${item.conversationId}`
  return null
}

export const notificationApi = {
  list: (pageNum = 1, pageSize = 20, signal?: AbortSignal) =>
    http.get<NotificationPage>(`/store/notifications?pageNum=${pageNum}&pageSize=${pageSize}`, { auth: true, signal }),
  unreadCount: (signal?: AbortSignal) =>
    http.get<string | number>('/store/notifications/unread-count', { auth: true, signal }),
  markRead: (id: string) => http.post<void>(`/store/notifications/${id}/read`, undefined, { auth: true }),
  /** 只标本店未读；返回本次实际变更条数（后端按 store 过滤，不会波及别店与个人消息） */
  markAllRead: () => http.post<string | number>('/store/notifications/read-all', undefined, { auth: true }),
}
