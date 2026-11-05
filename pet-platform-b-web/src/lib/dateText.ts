/**
 * 后端返回的是无时区的 LocalDateTime 文本（库与浏览器同为 +08:00），按本地时间解析即可。
 * 消息类时间只用"多久以前"，跨年才补年份——铃铛旁边那行字要回答的是"这是刚发生的还是昨天的"。
 */
export function timeAgo(iso: string, now: number = Date.now()): string {
  const t = Date.parse(iso)
  if (!Number.isFinite(t)) return ''
  const diff = now - t
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  const d = new Date(t)
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  const sameYear = d.getFullYear() === new Date(now).getFullYear()
  return sameYear ? `${d.getMonth() + 1}月${d.getDate()}日 ${hm}` : `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`
}

/** 本地历法的今天（不用 toISOString，避免 UTC 偏移把「今天」变成昨天）。 */
export function localToday(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** 服务日期是否在未来（ADR-054 日期闸的前端提示用）：严格晚于今天才算未来。 */
export function isFutureDate(iso: string | null | undefined): boolean {
  if (!iso) return false
  return iso > localToday()
}

/* ---- 以下为建单弹窗（BookAppointmentModal）所需，从 C 端 lib/dateText 同口径裁剪 ---- */

export function todayISO(): string {
  return localToday()
}

export function addDaysISO(base: string, days: number): string {
  const [y, m, d] = base.split('-').map(Number)
  const dt = new Date(y, m - 1, d + days)
  return `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, '0')}-${String(dt.getDate()).padStart(2, '0')}`
}

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

export function weekdayOf(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number)
  return WEEKDAYS[new Date(y, m - 1, d).getDay()]
}

/** 连续 n 天（含今天）。 */
export function dateRange(days: number, from = todayISO()): string[] {
  return Array.from({ length: days }, (_, i) => addDaysISO(from, i))
}

/** 「2026-09-25」→「9月25日 周五」；非法输入原样返回，不猜。 */
export function dateText(iso: string | null | undefined): string {
  if (!iso) return '时间待定'
  const [y, m, d] = iso.split('-').map(Number)
  if (!y || !m || !d) return iso
  return `${m}月${d}日 ${weekdayOf(iso)}`
}

/** 托管区间晚数（=ADR-025 计价天数：checkout − checkin）；非法输入返回 null，不猜。 */
export function nightsBetween(checkin: string | null, checkout: string | null): number | null {
  if (!checkin || !checkout) return null
  const a = Date.parse(`${checkin}T00:00:00`)
  const b = Date.parse(`${checkout}T00:00:00`)
  if (Number.isNaN(a) || Number.isNaN(b)) return null
  const n = Math.round((b - a) / 86400000)
  return n > 0 ? n : null
}

/** 时长文案：不足 1 小时用分钟、整小时用小时（托管类 1440 分钟应读作 24 小时）。 */
export function durationText(min: number | null | undefined): string {
  if (min == null || min <= 0) return '时长未登记'
  if (min < 60 || min % 60 !== 0) return `${min} 分钟`
  const h = min / 60
  return h >= 24 ? `${h / 24} 天${h % 24 ? `（${h} 小时）` : ''}` : `${h} 小时`
}

/** 后端 LocalDateTime（`yyyy-MM-ddTHH:mm:ss`，无时区）→「HH:mm」；非法返回 null。 */
export function timeText(iso: string | null | undefined): string | null {
  if (!iso) return null
  const m = /T(\d{2}:\d{2})/.exec(iso)
  return m ? m[1] : null
}
