import { http } from './client'
import type { PageData } from './dashboard'

/**
 * B 端门店订单接口（ADR-019 / P8 §4.3 :227，权限码 order:view:store）。
 * 与「客户/员工管理」两页不同，这里**不走 demoMode 兜底**——端点已随后端实现，
 * 拿不到就如实报错，避免用假订单掩盖权限或部署问题（整块开发的定义即接口就绪）。
 *
 * 契约要点：
 * - 金额一律分（Long→字符串），展示走 fenToYuanText；
 * - durationMin 是 Integer → 数字，不是字符串；
 * - 日期为 created_at 的**闭区间**（dateTo 含当日全天）；
 * - 手机号由后端脱敏（139****9904），前端不参与遮蔽，也没有明文可解；
 * - keyword=订单号前缀（服务端 LIKE 'kw%'，≥2 字符；与状态页签/日期可组合）。
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

export interface OrderCustomer {
  userId: string
  nickname: string | null
  phoneMasked: string | null
  /** NORMAL/DISABLED */
  status: string
}

/** 线下收款流水（ADR-006 §6）：仅详情接口返回；无线下流水时为 null。 */
export interface OfflinePaymentInfo {
  paymentNo: string
  /** 分 */
  amount: string
  /** PROCESSING=已登记待复核 / SUCCESS=已复核 */
  status: string
  registeredBy: string | null
  registrantName: string | null
  note: string | null
  confirmedBy: string | null
  createdAt: string | null
  paidAt: string | null
}

export interface OrderItem {
  id: string
  orderNo: string
  appointmentId: string | null
  status: OrderStatus | string
  /** 应付（分） */
  amount: string
  /** 实收（分）；未支付与已取消为 0 */
  paidAmount: string
  /** ONLINE/OFFLINE */
  paymentMethod: string | null
  payExpireAt: string | null
  paidAt: string | null
  createdAt: string | null
  serviceName: string | null
  durationMin: number | null
  /** null = 下单账号已注销/逻辑删除 */
  customer: OrderCustomer | null
  /** 仅详情返回；列表恒 null */
  offlinePayment: OfflinePaymentInfo | null
}

export interface OrderListParams {
  status?: OrderStatus
  dateFrom?: string
  dateTo?: string
  /** 订单号前缀（服务端 LIKE 'kw%'）；<2 字符后端 10001，前端只在 ≥2 时携带 */
  keyword?: string
  pageNum?: number
  pageSize?: number
}

export const ORDER_STATUSES: OrderStatus[] = [
  'WAIT_PAY',
  'PAID',
  'WAIT_SERVICE',
  'SERVING',
  'COMPLETED',
  'CANCELLED',
  'REFUNDING',
  'REFUNDED',
]

export const ordersApi = {
  list: (p: OrderListParams = {}) => {
    const q = new URLSearchParams()
    if (p.status) q.set('status', p.status)
    if (p.dateFrom) q.set('dateFrom', p.dateFrom)
    if (p.dateTo) q.set('dateTo', p.dateTo)
    if (p.keyword) q.set('keyword', p.keyword)
    q.set('pageNum', String(p.pageNum ?? 1))
    q.set('pageSize', String(p.pageSize ?? 20))
    return http.get<PageData<OrderItem>>(`/store/orders?${q.toString()}`, { auth: true })
  },
  /** 详情与列表同形状；行内展开走它，顺带在真实环境验证越权口径（跨店 10004）。 */
  detail: (id: string) => http.get<OrderItem>(`/store/orders/${id}`, { auth: true }),
  /**
   * 线下收款登记（ADR-006 §6）：只写 OFFLINE 待复核流水，不推进订单。
   * 登记≠到账；复核必须由另一名员工走 confirmOffline（双人原则，后端硬闸）。
   */
  registerOffline: (id: string, note?: string) =>
    http.post<OfflinePaymentInfo>(`/store/orders/${id}/offline-payment`, { note: note || undefined }, { auth: true }),
  /** 线下收款复核：复核人=登记人后端拒绝（10003）；成功后订单 PAID、预约恢复/确认可服务。 */
  confirmOffline: (id: string) =>
    http.post<OfflinePaymentInfo>(`/store/orders/${id}/offline-payment/confirm`, undefined, { auth: true }),
}

/**
 * 导出对账单（P5）：与列表同一组筛选参数（status/dateFrom/dateTo/keyword），**不带分页**——
 * 后端拉全量（上限 5000 行，超限 10001）。成功返回 CSV blob（UTF-8 BOM），失败仍是 JSON 信封抛 BizError。
 */
export async function exportOrdersCsv(
  p: Pick<OrderListParams, 'status' | 'dateFrom' | 'dateTo' | 'keyword'> = {},
): Promise<Blob> {
  const q = new URLSearchParams()
  if (p.status) q.set('status', p.status)
  if (p.dateFrom) q.set('dateFrom', p.dateFrom)
  if (p.dateTo) q.set('dateTo', p.dateTo)
  if (p.keyword) q.set('keyword', p.keyword)
  return http.download(`/store/orders/export?${q.toString()}`, { auth: true })
}

export const ORDER_STATUS_LABEL: Record<OrderStatus, string> = {
  WAIT_PAY: '待支付',
  PAID: '已支付',
  WAIT_SERVICE: '待服务',
  SERVING: '服务中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  REFUNDING: '退款中',
  REFUNDED: '已退款',
}

export const PAYMENT_METHOD_LABEL: Record<string, string> = {
  ONLINE: '线上支付',
  OFFLINE: '线下登记',
}

/** 本地历法日期（禁 toISOString：UTC 偏移会让"今天"在凌晨跳错一天）。 */
export function localDate(offsetDays = 0): string {
  const d = new Date()
  d.setDate(d.getDate() + offsetDays)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** 当前页实收合计（分，BigInt）——只是当前页，页面必须标注口径。 */
export function pagePaidSum(rows: OrderItem[]): string {
  return rows.reduce((acc, r) => acc + BigInt(r.paidAmount || '0'), 0n).toString()
}

/** ISO 时间戳 → 「2026-09-25 09:05」；无值返回破折号，不伪造时间。 */
export function orderTimeText(iso: string | null): string {
  if (!iso) return '—'
  const date = iso.slice(0, 10)
  const time = iso.includes('T') ? iso.slice(11, 16) : ''
  return time ? `${date} ${time}` : date
}
