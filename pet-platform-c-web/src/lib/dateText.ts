/** 日期工具：一律用本机历法日期拼字符串，避免 toISOString 的 UTC 偏移把日期算错一天。 */

export function toISODate(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export function todayISO(): string {
  return toISODate(new Date())
}

export function addDaysISO(base: string, days: number): string {
  const [y, m, d] = base.split('-').map(Number)
  return toISODate(new Date(y, m - 1, d + days))
}

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

export function weekdayOf(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number)
  return WEEKDAYS[new Date(y, m - 1, d).getDay()]
}

/** 入住区间的晚数（=ADR-025 的计价天数：checkout-checkin）；非法输入返回 null，不猜。 */
export function nightsBetween(checkin: string | null, checkout: string | null): number | null {
  if (!checkin || !checkout) return null
  const a = Date.parse(`${checkin}T00:00:00`)
  const b = Date.parse(`${checkout}T00:00:00`)
  if (Number.isNaN(a) || Number.isNaN(b)) return null
  const n = Math.round((b - a) / 86400000)
  return n > 0 ? n : null
}

/** 连续 n 天的日期条（含今天）。 */
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

/**
 * 后端 LocalDateTime 序列化为 `yyyy-MM-ddTHH:mm:ss`（无时区偏移，JacksonConfig 既定口径），
 * 补秒段保证各浏览器都按本地时间解析；容器与浏览器同为东八区，故不做额外换算。
 */
export function parseServerDateTime(s: string | null | undefined): Date | null {
  if (!s) return null
  const normalized = /^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})$/.exec(s)
  const d = new Date(normalized ? `${normalized[1]}T${normalized[2]}:${normalized[3]}:00` : s)
  return Number.isNaN(d.getTime()) ? null : d
}

/** 时长文案：不足 1 小时用分钟，整小时用小时（托管类 1440 分钟应读作 24 小时）。 */
export function durationText(min: number | null | undefined): string {
  if (min == null || min <= 0) return '时长未登记'
  if (min < 60 || min % 60 !== 0) return `${min} 分钟`
  const h = min / 60
  return h >= 24 ? `${h / 24} 天${h % 24 ? `（${h} 小时）` : ''}` : `${h} 小时`
}

/** 剩余时间 mm:ss（用于待支付倒计时）；已过期返回 null。 */
export function countdownText(until: string | null | undefined, now = new Date()): string | null {
  const t = parseServerDateTime(until)
  if (!t) return null
  const left = Math.floor((t.getTime() - now.getTime()) / 1000)
  if (left <= 0) return null
  const m = Math.floor(left / 60)
  const s = left % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

/**
 * 消息列表用的相对时间：刚刚 / N 分钟前 / N 小时前 / 昨天 / N 天前 / 绝对日期。
 * 服务器时钟超前（diff<0）时直接给绝对日期——"−3 分钟前"比"今天"更让人怀疑数据坏了。
 */
export function relativeTimeText(iso: string | null | undefined, now = new Date()): string {
  const t = parseServerDateTime(iso)
  if (!t) return iso ?? '未知时间'
  const diff = now.getTime() - t.getTime()
  const dateOnly = dateText(iso)
  if (diff < 0) return dateOnly
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hr = Math.floor(min / 60)
  if (hr < 24) return `${hr} 小时前`
  const day = Math.floor(hr / 24)
  if (day === 1) return `昨天 ${String(t.getHours()).padStart(2, '0')}:${String(t.getMinutes()).padStart(2, '0')}`
  if (day < 7) return `${day} 天前`
  return dateOnly
}
