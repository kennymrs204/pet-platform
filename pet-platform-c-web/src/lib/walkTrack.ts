import type { AppointmentWalkTrack } from '../api/appointment'

/**
 * C 端轨迹展示的纯函数（与 B 端 `lib/walkTrack.ts` 的展示算法同构——
 * 两个应用是独立包、无法共享源码；这里只做**格式化与画线**，不参与任何距离计算，
 * 里程/时长/点数一律取服务端持久化值，避免跨端出现第二套算法）。
 */
export function formatDistanceM(m: number | null | undefined): string {
  if (m == null || !Number.isFinite(m) || m <= 0) return '0 m'
  return m < 1000 ? `${Math.round(m)} m` : `${(m / 1000).toFixed(2)} km`
}

export function formatDurationSec(sec: number | null | undefined): string {
  const s = sec == null || !Number.isFinite(sec) || sec < 0 ? 0 : Math.round(sec)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const r = s % 60
  if (h > 0) return `${h} 小时 ${m} 分`
  if (m > 0) return `${m} 分 ${r} 秒`
  return `${r} 秒`
}

export function trackSummaryText(t: Pick<AppointmentWalkTrack, 'distanceM' | 'durationSec' | 'pointCount'>): string {
  return `${formatDistanceM(t.distanceM)} · ${formatDurationSec(t.durationSec)} · ${t.pointCount} 个轨迹点`
}

/**
 * 轨迹点 → SVG path（等距圆柱近似 + 等比缩放），只画示意路线。
 * 少于 2 点返回空串：调用方显示空态，不画假路线。
 */
export function trackSvgPath(
  polyline: ReadonlyArray<readonly [number, number]> | null | undefined,
  width = 120,
  height = 48,
  pad = 4,
): string {
  if (!polyline || polyline.length < 2) return ''
  let minLat = Infinity
  let maxLat = -Infinity
  let minLng = Infinity
  let maxLng = -Infinity
  for (const [lat, lng] of polyline) {
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) continue
    minLat = Math.min(minLat, lat)
    maxLat = Math.max(maxLat, lat)
    minLng = Math.min(minLng, lng)
    maxLng = Math.max(maxLng, lng)
  }
  if (!Number.isFinite(minLat) || !Number.isFinite(minLng)) return ''
  const spanLat = maxLat - minLat
  const spanLng = maxLng - minLng
  const innerW = Math.max(1, width - pad * 2)
  const innerH = Math.max(1, height - pad * 2)
  const scale =
    spanLat === 0 && spanLng === 0
      ? 0
      : Math.min(
          spanLng === 0 ? Infinity : innerW / spanLng,
          spanLat === 0 ? Infinity : innerH / spanLat,
        )
  const offsetX = pad + (innerW - spanLng * scale) / 2
  const offsetY = pad + (innerH - spanLat * scale) / 2
  return polyline
    .filter(([lat, lng]) => Number.isFinite(lat) && Number.isFinite(lng))
    .map(([lat, lng], i) => {
      const x = spanLng === 0 ? pad + innerW / 2 : offsetX + (lng - minLng) * scale
      const y = spanLat === 0 ? pad + innerH / 2 : offsetY + innerH - (lat - minLat) * scale
      return `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
}
