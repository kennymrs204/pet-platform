import type { WalkTrackPoint, WalkTrackSummary } from '../api/walking'

/**
 * GPS 轨迹的客户端状态机（纯函数，便于单测）。
 *
 * <p>职责边界：前端只负责**采集与缓冲**（用户主动开始、可暂停/继续、结束提交），
 * 距离/有效点数/实际记录时长一律以服务端清洗后的持久化值为准（见后端 TrackGeometry）。
 * 这里的时长只用于"录制中"的实时展示，标注为「已记录」，不作为最终事实。
 */
export const MAX_TRACK_POINTS = 2000

export interface RecordingState {
  appointmentId: string
  /** 幂等键：同一轨迹的每次重试都必须复用 */
  trackId: string
  /** 首个被接受点的采集时间（ms），不是任务开始时间 */
  startedAt: number
  points: WalkTrackPoint[]
  /** 已结束的暂停累计秒数 */
  pausedSec: number
  /** 当前是否处于暂停（非 null=暂停中，值为进入暂停的时刻） */
  pausedAt: number | null
}

/** 幂等键：优先用 crypto.randomUUID（安全上下文），否则退化为时间+随机。 */
export function newTrackId(): string {
  const c = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  return `track-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

export function startRecording(appointmentId: string, now = Date.now()): RecordingState {
  return {
    appointmentId,
    trackId: newTrackId(),
    startedAt: now,
    points: [],
    pausedSec: 0,
    pausedAt: null,
  }
}

function validPoint(p: WalkTrackPoint): boolean {
  return (
    Number.isFinite(p.lat) &&
    Number.isFinite(p.lng) &&
    p.lat >= -90 &&
    p.lat <= 90 &&
    p.lng >= -180 &&
    p.lng <= 180 &&
    Number.isFinite(p.atMillis) &&
    p.atMillis > 0
  )
}

/** 精度归一：有限正数才算已知，其余（0/负数/NaN）按未知处理，不因此丢点。 */
export function normalizeAccuracyM(accuracyM: number | null | undefined): number {
  return typeof accuracyM === 'number' && Number.isFinite(accuracyM) && accuracyM > 0 ? accuracyM : 0
}

/**
 * 接受一个定位点。本地只做三件最低限度的过滤（越界/时间不前进/同坐标），
 * 跳点与抖动剔除交给服务端（唯一权威），避免两处规则漂移。
 * 返回 accepted=false 表示该点被丢弃（不改变状态）。
 */
export function acceptPoint(
  s: RecordingState,
  p: WalkTrackPoint,
): { state: RecordingState; accepted: boolean } {
  if (!validPoint(p)) return { state: s, accepted: false }
  const point = { ...p, accuracyM: normalizeAccuracyM(p.accuracyM) }
  if (s.points.length === 0) {
    return {
      state: { ...s, startedAt: point.atMillis, points: [point] },
      accepted: true,
    }
  }
  const last = s.points[s.points.length - 1]
  if (point.atMillis <= last.atMillis) return { state: s, accepted: false }
  if (point.lat === last.lat && point.lng === last.lng) return { state: s, accepted: false }
  if (s.points.length >= MAX_TRACK_POINTS) return { state: s, accepted: false }
  return { state: { ...s, points: [...s.points, point] }, accepted: true }
}

export function pauseRecording(s: RecordingState, now = Date.now()): RecordingState {
  if (s.pausedAt !== null) return s
  return { ...s, pausedAt: now }
}

export function resumeRecording(s: RecordingState, now = Date.now()): RecordingState {
  if (s.pausedAt === null) return s
  const added = Math.max(0, Math.round((now - s.pausedAt) / 1000))
  return { ...s, pausedAt: null, pausedSec: s.pausedSec + added }
}

/** 录制中的实时"已记录时长"（秒）：暂停时不增长；未暂停时按墙钟走。 */
export function recordingDurationSec(s: RecordingState, now = Date.now()): number {
  const end = s.pausedAt !== null ? s.pausedAt : now
  const total = Math.max(0, Math.round((end - s.startedAt) / 1000) - s.pausedSec)
  return Math.max(0, total)
}

/** 结束时应提交的暂停秒数（若仍在暂停，先结算当前这段）。 */
export function finalPausedSec(s: RecordingState, now = Date.now()): number {
  if (s.pausedAt === null) return s.pausedSec
  return s.pausedSec + Math.max(0, Math.round((now - s.pausedAt) / 1000))
}

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

/** 保存后（或读历史时）的摘要一行：距离 · 时长 · 点数。 */
export function trackSummaryText(t: Pick<WalkTrackSummary, 'distanceM' | 'durationSec' | 'pointCount'>): string {
  return `${formatDistanceM(t.distanceM)} · ${formatDurationSec(t.durationSec)} · ${t.pointCount} 个轨迹点`
}

/** 最近一个已知精度（米）；没有任何已知精度返回 null（不编一个"精度良好"）。 */
export function lastAccuracyM(points: ReadonlyArray<WalkTrackPoint> | null | undefined): number | null {
  if (!points || points.length === 0) return null
  for (let i = points.length - 1; i >= 0; i--) {
    const a = normalizeAccuracyM(points[i].accuracyM)
    if (a > 0) return a
  }
  return null
}

/** 精度文案：未知就说未知。 */
export function accuracyText(m: number | null | undefined): string {
  const a = normalizeAccuracyM(m ?? 0)
  return a === 0 ? '精度未知' : `定位精度约 ${Math.round(a)} 米`
}

/** 与服务端 `TrackGeometry.MAX_ACCURACY_RELIABLE_M` 同值：超过它的点不计里程（只保留时间轴）。 */
export const POOR_ACCURACY_M = 50

export function isPoorAccuracy(m: number | null | undefined): boolean {
  return normalizeAccuracyM(m ?? 0) > POOR_ACCURACY_M
}

/** 录制状态（需求五要求七种状态可区分）。 */
export type TrackUiState =
  | 'idle'
  | 'recording'
  | 'paused'
  | 'geo-unavailable'
  | 'saving'
  | 'save-failed'
  | 'saved'

export const TRACK_UI_LABEL: Record<TrackUiState, string> = {
  idle: '未开始',
  recording: '录制中',
  paused: '已暂停',
  'geo-unavailable': '定位暂不可用',
  saving: '正在保存',
  'save-failed': '保存失败',
  saved: '保存成功',
}

/**
 * 单一判据推导当前状态，避免页面上两行文案互相矛盾。
 * 优先级：保存中 > 保存失败 > 已暂停 > 定位暂不可用 > 录制中 > 已保存 > 未开始。
 */
export function trackUiState(args: {
  hasSavedTrack: boolean
  recording: RecordingState | null
  geoError: string | null
  saving: boolean
  saveError: string | null
}): TrackUiState {
  if (args.saving) return 'saving'
  if (args.saveError) return 'save-failed'
  if (args.recording) {
    if (args.recording.pausedAt !== null) return 'paused'
    return args.geoError ? 'geo-unavailable' : 'recording'
  }
  return args.hasSavedTrack ? 'saved' : 'idle'
}

/**
 * 把 [lat,lng] 序列投影成 SVG path（等距圆柱近似 + 等比缩放）。
 * 项目未接地图 SDK，用内联 SVG 画示意路线是"现有能力里改动最小"的方案，不引入依赖/密钥。
 * 少于 2 个点返回空串（调用方显示空态，不画假路线）。
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
  // 等比：取较大的跨度作为基准，避免扁平化失真
  const scale = spanLat === 0 && spanLng === 0 ? 0 : Math.min(
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
