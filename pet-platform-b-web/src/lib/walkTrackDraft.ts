import type { WalkTrackPoint } from '../api/walking'
import { normalizeAccuracyM, type RecordingState } from './walkTrack'

/**
 * 未提交轨迹的浏览器草稿（sessionStorage）。
 *
 * <p>定位：**只降低误刷新/误离开导致的丢失概率，不保证不丢**（关标签页、隐私模式、存储被清理都会消失）。
 * 因而这里从不宣称"绝不丢"，页面也必须把这句话写出来。
 *
 * <p>边界：
 * - 只存轨迹点与状态，**不存任何登录凭证**；
 * - 必须带 `userId + storeId + appointmentId + trackId`，恢复前逐项匹配（换人/换店/换单都不恢复）；
 * - 恢复后**不自动开始采集**（返回的状态是"已暂停"，`pausedAt` 锚在最后一个点上，
 *   这样显示的记录时长仍是最后一次采集时的值；用户点"继续记录"才重新 watchPosition）。
 * - 服务端已有轨迹时不得用草稿覆盖（由页面在恢复前检查 `task.track`，提交侧另有 10005 兜底）。
 */
export const DRAFT_KEY = 'ppb.walkTrackDraft.v1'
const DRAFT_VERSION = 1
const MAX_DRAFT_POINTS = 2000

export interface WalkTrackDraftRecord {
  v: number
  userId: string
  storeId: string
  appointmentId: string
  trackId: string
  startedAt: number
  points: WalkTrackPoint[]
  pausedSec: number
  /** 写入时间（用于展示"最后更新"） */
  savedAt: number
}

/** 便于测试注入：任何满足 getItem/setItem/removeItem 的对象。 */
export type DraftStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

function defaultStorage(): DraftStorage | null {
  try {
    return typeof sessionStorage === 'undefined' ? null : sessionStorage
  } catch {
    return null
  }
}

export function draftFromState(
  s: RecordingState,
  ctx: { userId: string; storeId: string },
  now = Date.now(),
): WalkTrackDraftRecord {
  return {
    v: DRAFT_VERSION,
    userId: ctx.userId,
    storeId: ctx.storeId,
    appointmentId: s.appointmentId,
    trackId: s.trackId,
    startedAt: s.startedAt,
    points: s.points.map((p) => ({
      lat: p.lat,
      lng: p.lng,
      atMillis: p.atMillis,
      accuracyM: normalizeAccuracyM(p.accuracyM),
    })),
    pausedSec: s.pausedSec,
    savedAt: now,
  }
}

/** @returns 是否真的写成功（存储不可用/超限时为 false，调用方不必当作错误） */
export function writeDraft(d: WalkTrackDraftRecord, storage: DraftStorage | null = defaultStorage()): boolean {
  if (!storage) return false
  try {
    storage.setItem(DRAFT_KEY, JSON.stringify(d))
    return true
  } catch {
    return false
  }
}

/** 读取并做形状校验；任何不合法（含版本不符）都返回 null，不把脏数据交给 UI。 */
export function readDraft(storage: DraftStorage | null = defaultStorage()): WalkTrackDraftRecord | null {
  if (!storage) return null
  let raw: string | null = null
  try {
    raw = storage.getItem(DRAFT_KEY)
  } catch {
    return null
  }
  if (!raw) return null
  try {
    const d = JSON.parse(raw) as Partial<WalkTrackDraftRecord>
    if (!d || d.v !== DRAFT_VERSION) return null
    if (!isNonEmptyString(d.userId) || !isNonEmptyString(d.storeId)) return null
    if (!isNonEmptyString(d.appointmentId) || !isNonEmptyString(d.trackId)) return null
    if (typeof d.startedAt !== 'number' || !Number.isFinite(d.startedAt) || d.startedAt <= 0) return null
    if (typeof d.pausedSec !== 'number' || !Number.isFinite(d.pausedSec) || d.pausedSec < 0) return null
    if (!Array.isArray(d.points) || d.points.length === 0 || d.points.length > MAX_DRAFT_POINTS) return null
    const points = d.points.filter(validPoint)
    if (points.length !== d.points.length) return null
    return {
      v: DRAFT_VERSION,
      userId: d.userId,
      storeId: d.storeId,
      appointmentId: d.appointmentId,
      trackId: d.trackId,
      startedAt: d.startedAt,
      points,
      pausedSec: d.pausedSec,
      savedAt: typeof d.savedAt === 'number' && Number.isFinite(d.savedAt) ? d.savedAt : 0,
    }
  } catch {
    return null
  }
}

export function clearDraft(storage: DraftStorage | null = defaultStorage()): void {
  if (!storage) return
  try {
    storage.removeItem(DRAFT_KEY)
  } catch {
    /* ignore */
  }
}

/** 草稿归属匹配：用户、门店必配；给了 appointmentId 时还要匹配预约。 */
export function draftMatches(
  d: Pick<WalkTrackDraftRecord, 'userId' | 'storeId' | 'appointmentId'>,
  ctx: { userId: string | null | undefined; storeId: string | null | undefined; appointmentId?: string | null },
): boolean {
  if (!ctx.userId || !ctx.storeId) return false
  if (d.userId !== ctx.userId || d.storeId !== ctx.storeId) return false
  if (ctx.appointmentId != null && d.appointmentId !== ctx.appointmentId) return false
  return true
}

/**
 * 草稿 → 录制状态：**暂停态**（pausedAt 锚在最后一个点上）。
 * 这样显示的"已记录时长"仍等于最后一次采集时的值；点继续后，从该锚点到恢复时刻的空档
 * 会计入暂停秒数并与服务端口径一致（服务端用时长的定义是"末点−首点−暂停"）。
 */
export function draftToState(d: WalkTrackDraftRecord, now = Date.now()): RecordingState {
  const last = d.points[d.points.length - 1]
  return {
    appointmentId: d.appointmentId,
    trackId: d.trackId,
    startedAt: d.startedAt,
    points: d.points,
    pausedSec: d.pausedSec,
    pausedAt: last ? last.atMillis : now,
  }
}

export function draftPointsText(d: Pick<WalkTrackDraftRecord, 'points' | 'savedAt'>): string {
  return `${d.points.length} 个定位点`
}

function isNonEmptyString(v: unknown): v is string {
  return typeof v === 'string' && v.length > 0
}

function validPoint(p: unknown): p is WalkTrackPoint {
  if (!p || typeof p !== 'object') return false
  const q = p as WalkTrackPoint
  return (
    Number.isFinite(q.lat) &&
    Number.isFinite(q.lng) &&
    q.lat >= -90 &&
    q.lat <= 90 &&
    q.lng >= -180 &&
    q.lng <= 180 &&
    Number.isFinite(q.atMillis) &&
    q.atMillis > 0 &&
    Number.isFinite(q.accuracyM ?? 0) &&
    (q.accuracyM ?? 0) >= 0
  )
}
