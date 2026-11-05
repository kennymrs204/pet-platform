import { AppointmentSelfView } from '../api/appointment'
import { OrderView } from '../api/order'
import { PetVO } from '../api/pet'
import { dateText } from './dateText'

/**
 * 订单看板的本地 join（口径见 api/order.ts 头注释）：
 * 预约行提供宠物/日期/时段/生命周期状态与总额快照，订单行提供金额与支付状态，
 * 宠物与门店名分别由 /customer/pets、/public/stores 解析。
 */

export const APPT_STATUS_TEXT: Record<string, string> = {
  PENDING_PAYMENT: '待支付',
  CONFIRMED: '已确认',
  /** ADR-043：已付预约提交取消申请后的中间态——还没取消，等门店审核退款 */
  CANCEL_REQUESTED: '取消申请审核中',
  WAIT_SERVICE: '待服务',
  SERVING: '服务中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
}

/** 徽章配色：待支付与待审核=金（都是"等你/等门店做决定"）、进行中系=绿、取消=灰、完成=深蓝灰。 */
export function apptStatusTone(status: string): string {
  if (status === 'PENDING_PAYMENT' || status === 'CANCEL_REQUESTED') return 'bg-gold/15 text-gold-dark ring-1 ring-gold/40'
  if (status === 'CANCELLED') return 'bg-warmgray text-muted ring-1 ring-navy/5'
  if (status === 'COMPLETED') return 'bg-navy/8 text-navy/70 ring-1 ring-navy/10'
  return 'bg-emerald-50 text-emerald-600 ring-1 ring-emerald-200/70'
}

/**
 * 页签 → 预约状态集合。`?status=` 后端是单值精确匹配，多状态页签靠并发请求合并。
 * CANCEL_REQUESTED 归在「待服务」：申请还没批，这条预约依然占着台位、门店仍可能要服务它
 * （ADR-043 §5）——放进「已取消」会骗人。
 */
export const TABS: { key: string; label: string; statuses: string[] }[] = [
  { key: 'ALL', label: '全部', statuses: [] },
  { key: 'WAIT_PAY', label: '待支付', statuses: ['PENDING_PAYMENT'] },
  { key: 'TO_SERVE', label: '待服务', statuses: ['CONFIRMED', 'CANCEL_REQUESTED', 'WAIT_SERVICE'] },
  { key: 'SERVING', label: '进行中', statuses: ['SERVING'] },
  { key: 'DONE', label: '已完成', statuses: ['COMPLETED'] },
  { key: 'CANCELLED', label: '已取消', statuses: ['CANCELLED'] },
]

/**
 * 可取消状态（ADR-023 §2 + ADR-043 §2）：仅 PENDING_PAYMENT（未付当场关单）与
 * CONFIRMED（已付=提交取消申请，进审核，不立即取消）。
 * CANCEL_REQUESTED 不给按钮 ⇒ 重复提交在界面上不可能（后端也以 50003 兜住）；
 * WAIT_SERVICE/SERVING 后端回 50004、COMPLETED/CANCELLED 回 50003，故都不给入口。
 */
export function canCancel(status: string): boolean {
  return status === 'PENDING_PAYMENT' || status === 'CONFIRMED'
}

export interface BoardRow {
  appt: AppointmentSelfView
  order: OrderView | null
  pet: PetVO | null
  storeName: string
}

/**
 * 门店名解析器：由调用方提供（数据来自 GET /public/stores，ADR-022 已上线）。
 * 不再从演示常量取名，避免真实门店数据被静默标成 "门店 {id}"。
 */
export type StoreNameOf = (storeId: string) => string

export const fallbackStoreName: StoreNameOf = (id) => `门店 ${id}`

/** 行内时间文案：托管显示区间，即时服务显示日期+时段。 */
export function apptWhenText(a: AppointmentSelfView): string {
  if (a.boardingCheckinDate) {
    return `${dateText(a.boardingCheckinDate)} 至 ${dateText(a.boardingCheckoutDate)}`
  }
  return `${dateText(a.serviceDate)}${a.startTime ? ` ${a.startTime}` : ''}`
}

export function buildRows(appts: AppointmentSelfView[], orders: OrderView[], pets: PetVO[], nameOf: StoreNameOf): BoardRow[] {
  const orderByAppt = new Map(orders.map((o) => [String(o.appointmentId), o]))
  const petById = new Map(pets.map((p) => [String(p.id), p]))
  return appts.map((a) => ({
    appt: a,
    order: orderByAppt.get(String(a.id)) ?? null,
    pet: petById.get(String(a.petId)) ?? null,
    storeName: nameOf(String(a.storeId)),
  }))
}

/** 多状态并发合并后按创建时间倒序（与后端 created_at desc → id desc 同口径）。 */
export function mergeAppointments(groups: AppointmentSelfView[][]): AppointmentSelfView[] {
  const seen = new Set<string>()
  const all: AppointmentSelfView[] = []
  for (const g of groups) {
    for (const a of g) {
      if (seen.has(String(a.id))) continue
      seen.add(String(a.id))
      all.push(a)
    }
  }
  return all.sort((x, y) => {
    const k = String(y.createdAt).localeCompare(String(x.createdAt))
    return k !== 0 ? k : String(y.id).localeCompare(String(x.id))
  })
}
