import { useRef, useState, useEffect, useSyncExternalStore } from 'react'
import { Link } from 'react-router-dom'
import Layout from '../components/layout/Layout'
import Card from '../components/ui/Card'
import {
  IconBulb,
  IconCheckCircle,
  IconChevronDown,
  IconClock,
  IconDog,
  IconHeartPulse,
  IconImage,
  IconMapPin,
  IconPlus,
  IconShield,
  IconUser,
  IconUsers,
} from '../components/ui/icons'
import { useToast } from '../components/ui/Toast'
import PetHealthDrawer from '../components/pet/PetHealthDrawer'
import { storeContext } from '../auth/storeContext'
import { BizError } from '../api/types'
import { fenToYuanText } from '../api/dashboard'
import { ROLE_LABEL, employeesApi, type EmployeeRow } from '../api/employees'
import {
  bathingApi,
  buildStaffLoad,
  staffingSummary,
  dateOfOffset,
  dateTimeText,
  durationText,
  type TaskResult,
} from '../api/bathing'
import {
  WALK_QUEUE_STATUSES,
  WALK_QUEUE_STATUS_LABEL,
  filterWalkTasks,
  walkTabCounts,
  walkTimeText,
  walkingApi,
  type WalkQueueStatus,
  type WalkRecordRef,
  type WalkingTask,
} from '../api/walking'
import { mediaSrc } from '../lib/mediaSrc'
import { isFutureDate } from '../lib/dateText'
import { cx } from '../lib/cx'
import { TONE } from '../lib/tone'
import { canApproveRefund, refundsApi, REFUND_STATUS_LABEL, type RefundItem } from '../api/refunds'
import { orderTimeText } from '../api/orders'
import { canReviewCancel, pendingRefundByAppointment } from '../lib/walkCancel'
import { canViewPetHealth } from '../lib/petManage'
import { healthTargetFor, type WalkHealthPetRef } from '../lib/walkHealth'
import { UploadError, uploadImage } from '../api/upload'
import {
  MAX_WALK_PHOTOS,
  photoPickError,
  recordPhotoCount,
  recordPhotoUrls,
  remainingPhotoSlots,
} from '../lib/walkPhotos'
import {
  acceptPoint,
  accuracyText,
  finalPausedSec,
  formatDistanceM,
  formatDurationSec,
  isPoorAccuracy,
  lastAccuracyM,
  pauseRecording,
  recordingDurationSec,
  resumeRecording,
  startRecording,
  trackSvgPath,
  trackSummaryText,
  trackUiState,
  TRACK_UI_LABEL,
  type RecordingState,
} from '../lib/walkTrack'
import {
  clearDraft,
  draftFromState,
  draftMatches,
  draftPointsText,
  draftToState,
  readDraft,
  writeDraft,
  type WalkTrackDraftRecord,
} from '../lib/walkTrackDraft'
import { tokenStore } from '../auth/tokenStore'
import type { Tone } from '../mock/dashboard'

type Tab = 'ALL' | WalkQueueStatus
type View = 'STORE' | 'MINE'

/** 轨迹录制控制器：把"录制中状态 + 动作 + 保存结果"一次性传给行内组件，避免散装 props 漂移。 */
interface TrackController {
  recording: RecordingState | null
  geoError: string | null
  saveError: string | null
  /** 是否为"从草稿恢复"的暂停态（文案要明确：用户点继续才开始采集） */
  restored: boolean
  /** 有未处理的草稿时禁止新开一条轨迹（先恢复或丢弃） */
  hasPendingDraft: boolean
  busy: boolean
  /** 每秒推进的时钟，仅用于实时展示"已记录时长" */
  now: number
  onStart: (t: WalkingTask) => void
  onPause: () => void
  onResume: () => void
  onStop: (t: WalkingTask) => void
  onDiscard: () => void
}

/**
 * B 端遛狗服务 = 遛狗履约看板，数据全是真实接口（ADR-049）：
 * `GET /store/walking/queue` + 本店名册 `GET /store/employees`。**不留演示回退**：拉不到就报错，不放假数据。
 * 洗护看板刻意排除遛狗单、「我的任务」只有本人视角，全店遛狗队列由 ADR-049 补齐读侧。
 *
 * 视图口径由服务端决定：持 `appointment:view:store`（店长/前台）拿全店，只有 `task:accept` 的角色
 * （遛狗师）被服务端强制收窄成本人任务（出参 `storeWideScope=false`）。页面上的「店看板 / 我的任务」
 * 只是在这份已收窄的结果上再隐一层，前端不自己判权限。
 *
 * 写侧四动作全部已接：到店签到（ADR-042）、指派/改派、开始、完成＋履约记录（ADR-048——完成遛狗时
 * 后端同事务落 `ful_walk_record`，表里只有备注，没有执行项目/照片列；里程/轨迹/排便次数刻意不采集）。
 */
export default function WalkingPage() {
  const { push } = useToast()
  const ctx = useSyncExternalStore(storeContext.subscribe, storeContext.get)
  const storeId = ctx.storeId
  const currentRoles = ctx.candidates.find((s) => s.storeId === storeId)?.roles ?? []
  const canViewHealth = canViewPetHealth(ctx.platformAdmin, currentRoles)

  const [view, setView] = useState<View>('STORE')
  const [tab, setTab] = useState<Tab>('ALL')
  const [date, setDate] = useState(dateOfOffset(0))
  const [openId, setOpenId] = useState<string | null>(null)
  /** 健康抽屉主语：只在用户点击后从任务里的真实 pet.id 生成，不随队列逐行预取 */
  const [healthPet, setHealthPet] = useState<WalkHealthPetRef | null>(null)
  // 同一时刻只允许一次动作在飞：双击/连点会打两发 POST，虽然后端幂等但回执会闪两条
  const [checkingId, setCheckingId] = useState<string | null>(null)
  const [writingId, setWritingId] = useState<string | null>(null)
  /** 取消审核在飞时按退款单 id 记（不是按预约），避免把同一单的两笔退款都当成"同一动作" */
  const [reviewingId, setReviewingId] = useState<string | null>(null)
  /** 照片上传/关联在飞时按预约记：同一行禁用入口，避免重复提交与重复关联 */
  const [photoBusyId, setPhotoBusyId] = useState<string | null>(null)

  /**
   * GPS 轨迹录制状态（只存内存，结束才提交）：trackId 在开始时固定，重试沿用同一个值；
   * 距离/时长/点数一律以服务端保存后的值为准，这里只做实时展示。
   */
  const [recording, setRecording] = useState<RecordingState | null>(null)
  const [geoError, setGeoError] = useState<string | null>(null)
  const [trackBusyId, setTrackBusyId] = useState<string | null>(null)
  const [nowTick, setNowTick] = useState(() => Date.now())
  /** 保存失败的原因（保留缓冲与 trackId，可重试；成功/开始新录制后清除） */
  const [trackSaveError, setTrackSaveError] = useState<string | null>(null)
  /** 从浏览器草稿恢复的暂停态：文案必须说明"点继续才开始采集" */
  const [restored, setRestored] = useState(false)
  /** 未提交的轨迹草稿（只在用户/门店/预约匹配时提示恢复） */
  const [pendingDraft, setPendingDraft] = useState<WalkTrackDraftRecord | null>(null)
  const recordingRef = useRef<RecordingState | null>(null)
  const watchRef = useRef<number | null>(null)
  const draftWrittenAtRef = useRef(0)
  /** 草稿归属键：登录用户 + 当前门店（不存任何凭证，只做匹配） */
  const [draftUserId] = useState(() => tokenStore.getUserId())
  const draftCtxRef = useRef({ userId: draftUserId, storeId })
  draftCtxRef.current = { userId: draftUserId, storeId }

  const mineOnly = view === 'MINE'
  // 当天全量遛狗单一次取全：页签计数在这份真数据上现算，切筛选不再发第二次请求
  const queue = useAsync(() => walkingApi.queue(date), `${storeId}|${date}`)
  // 名册要 emp:view（店长、前台有；遛狗师没有）→ 10003 走「无权限」空态而不是红错卡
  const roster = useAsync(() => employeesApi.list(), storeId)
  /**
   * 待处理退款：取消审核的申请信息（金额/理由/时间/退款单号）与"能否审批"都来自这张真实表，
   * 不复用队列里的任何推断字段。`order:refund:view` 是 V4 授予全部员工角色，正常情况下不 403；
   * 万一 10003，`useAsync` 会落到 denied 空态，页面只显示"看不到申请详情"，不编数据。
   */
  const refunds = useAsync(() => refundsApi.page('APPLYING', 1, 100), storeId ? `${storeId}|walk-refunds` : null)
  const canApprove = canApproveRefund(ctx.candidates.find((s) => s.storeId === storeId)?.roles ?? [])
  const pendingRefunds = refunds.block.state === 'ready' ? refunds.block.data.list : []
  const refundByAppointment = pendingRefundByAppointment(pendingRefunds)
  const refundsDenied = refunds.block.state === 'denied'
  /** 待处理退款按 100 条一页；总数更多时如实提示"可能未匹配"，不假装全都在 */
  const refundsPartial =
    refunds.block.state === 'ready' && Number(refunds.block.data.total) > pendingRefunds.length

  const day = queue.block.state === 'ready' ? queue.block.data : null
  const list = day?.list ?? []
  const status = tab === 'ALL' ? undefined : tab
  const shown = filterWalkTasks(list, { status, mineOnly })
  const counts = day ? walkTabCounts(list) : null
  const staffRows = roster.block.state === 'ready' && day ? buildStaffLoad(roster.block.data, list) : null
  const rosterRows = roster.block.state === 'ready' ? roster.block.data : []

  /** 签到后必须回队列拉真相：状态由后端条件更新决定，前端不就地把自己"改成"待服务。 */
  const doCheckin = async (t: WalkingTask) => {
    if (checkingId) return
    setCheckingId(t.appointmentId)
    const who = t.pet?.name ?? t.appointmentNo
    try {
      const r = await bathingApi.checkin(t.appointmentId)
      push(r.changed ? `${who} 已签到 → 待服务` : `${who} 此前已签到（本次未改变状态）`, 'success')
      setOpenId(null)
      queue.reload()
    } catch (err) {
      push(err instanceof BizError ? err.message : '签到失败，请稍后重试', 'error')
    } finally {
      setCheckingId(null)
    }
  }

  /**
   * 履约三动作（ADR-048）共用一条纪律：**回执说了什么就显示什么，然后回队列拉真相**。
   * `changed=false` 是幂等重放（同事已点过 / 自己双击），按成功回灰字提示而不是红色错误。
   */
  const runTask = async (t: WalkingTask, label: string, call: () => Promise<TaskResult>) => {
    if (writingId) return
    setWritingId(t.appointmentId)
    const who = t.pet?.name ?? t.appointmentNo
    try {
      const r = await call()
      push(r.changed ? `${who}：${label} → ${r.status}` : `${who}：该操作此前已完成（本次未改变状态）`, 'success')
      setOpenId(null)
      queue.reload()
    } catch (err) {
      push(err instanceof BizError ? err.message : `${label}失败，请稍后重试`, 'error')
    } finally {
      setWritingId(null)
    }
  }
  const doAssign = (t: WalkingTask, employeeId: string) =>
    runTask(t, '已指派', () => bathingApi.assignEmployee(t.appointmentId, employeeId))
  const doStart = (t: WalkingTask) => runTask(t, '开始服务', () => bathingApi.startService(t.appointmentId))
  const doFinish = (t: WalkingTask, remark: string) =>
    runTask(t, '完成服务', () => bathingApi.finishService(t.appointmentId, { remark: remark.trim() || undefined }))

  /** 点击时才解析目标：无 pet:health:view 或宠物已删除时不打开（入口本身也不渲染）。 */
  const openHealth = (t: WalkingTask) => {
    const target = healthTargetFor(t, canViewHealth)
    if (target) setHealthPet(target)
  }

  /**
   * 取消申请审核：**复用订单管理同一对端点**（`POST /store/refunds/{id}/approve|reject`，ADR-037/043），
   * 不新开审批接口、不自己改预约状态——同意后由后端四步 CAS 链退款成功再经事件把预约置 CANCELLED；
   * 拒绝则退款单 REJECTED、预约恢复 CONFIRMED。
   * 成功后队列与退款列表一起重拉（状态与角标都来自服务端）；失败只提示、不刷新、不把行改成"已处理"。
   */
  const doReviewCancel = async (t: WalkingTask, refund: RefundItem, kind: 'approve' | 'reject', rejectReason?: string) => {
    if (reviewingId) return
    setReviewingId(refund.id)
    const who = t.pet?.name ?? t.appointmentNo
    try {
      const done = kind === 'approve'
        ? await refundsApi.approve(refund.id)
        : await refundsApi.reject(refund.id, (rejectReason ?? '').trim())
      push(
        kind === 'approve'
          ? `已同意取消：${who} 的预约已取消，退款单 ${done.refundNo} 记为已退款（当前为模拟执行，未走真实渠道）`
          : `已拒绝取消：退款单 ${done.refundNo} 已拒绝，${who} 的预约恢复为「${WALK_QUEUE_STATUS_LABEL.CONFIRMED}」`,
        'success',
      )
      setOpenId(null)
      queue.reload()
      refunds.reload()
    } catch (err) {
      // 并发被同事先处理时服务端已回 60005「退款单状态已变化」，按原样显示即可
      push(err instanceof BizError ? err.message : '取消审核失败，请稍后重试', 'error')
    } finally {
      setReviewingId(null)
    }
  }

  /**
   * 添加遛狗照片（仅已完成、且已有履约记录的行）：先逐张直传对象存储拿 key，再**一次性**关联到记录。
   * 授权（task:finish + 执行人/店长范围 + 门店 + DOG_WALK + 完成态）全在服务端；
   * 任何一步失败都只提示、不刷新、不在本地追加"看起来成功"的照片——页面始终显示服务端真值。
   * 本操作只改 ful_walk_record.photos，不推进/回退任何预约状态。
   */
  const doAddPhotos = async (t: WalkingTask, files: File[]) => {
    if (photoBusyId) return
    const bad = photoPickError(files.length, remainingPhotoSlots(recordPhotoCount(t.record)))
    if (bad) {
      push(bad, 'error')
      return
    }
    setPhotoBusyId(t.appointmentId)
    const who = t.pet?.name ?? t.appointmentNo
    try {
      const keys: string[] = []
      for (const file of files) {
        keys.push(await uploadImage(file, 'WALK_PHOTO'))
      }
      const saved = await walkingApi.addPhotos(t.appointmentId, keys)
      push(`${who}：已添加 ${keys.length} 张照片（该单共 ${saved.photos.length} 张）`, 'success')
      queue.reload()
    } catch (err) {
      // 上传失败/关联被拒都按原样说；不做本地追加、不刷新成"成功"
      push(err instanceof UploadError || err instanceof BizError ? err.message : '照片添加失败，请重试', 'error')
    } finally {
      setPhotoBusyId(null)
    }
  }

  /* ---- GPS 轨迹：用户主动开始 + 浏览器授权定位后才采集；离开页面即停（不做后台定位） ---- */

  const stopWatch = () => {
    if (watchRef.current !== null) {
      navigator.geolocation.clearWatch(watchRef.current)
      watchRef.current = null
    }
  }

  /**
   * 把未提交的轨迹写进 sessionStorage 草稿（只降丢失概率，不保证不丢）。
   * 只存坐标与状态，不存凭证；点数 ≥1 才写（没有数据可丢）。默认节流 3s，暂停/离开/卸载时强制写。
   */
  const persistDraft = (force: boolean) => {
    const cur = recordingRef.current
    const { userId: uid, storeId: sid } = draftCtxRef.current
    if (!cur || cur.points.length === 0 || !uid || !sid) return
    const now = Date.now()
    if (!force && now - draftWrittenAtRef.current < 3000) return
    if (writeDraft(draftFromState(cur, { userId: uid, storeId: sid }, now))) {
      draftWrittenAtRef.current = now
    }
  }

  // 录制中每秒走一次时钟，仅用于"已记录时长"的实时展示（最终值以服务端为准）
  useEffect(() => {
    if (!recording || recording.pausedAt !== null) return
    const timer = setInterval(() => setNowTick(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [recording])

  // 误刷新/误关闭：先落草稿，再让浏览器弹"离开会丢失"的原生确认（文案不可自定义）
  useEffect(() => {
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      const cur = recordingRef.current
      if (!cur || cur.points.length === 0) return
      persistDraft(true)
      e.preventDefault()
      e.returnValue = '轨迹尚未保存，离开页面可能丢失'
      return e.returnValue
    }
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') persistDraft(true)
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      window.removeEventListener('beforeunload', onBeforeUnload)
      document.removeEventListener('visibilitychange', onVisibility)
    }
    // eslint 依赖：persistDraft 只读 ref，不需要随渲染重建
  }, [])

  // 卸载（路由离开遛狗页）：停表 + 落草稿；页面外不做任何持续定位
  useEffect(() => () => {
    persistDraft(true)
    stopWatch()
  }, [])

  // 切店：未提交的轨迹直接丢弃并清草稿——轨迹绑定门店/执行人，跨店继续会错误归属
  const prevStoreRef = useRef(storeId)
  useEffect(() => {
    if (prevStoreRef.current === storeId) return
    prevStoreRef.current = storeId
    stopWatch()
    recordingRef.current = null
    setRecording(null)
    setGeoError(null)
    setTrackSaveError(null)
    setRestored(false)
    setPendingDraft(null)
    setHealthPet(null)
    clearDraft()
    draftWrittenAtRef.current = 0
  }, [storeId])

  // 打开页面：只恢复"同一登录用户 + 同一门店"的草稿；换人/换店一律清掉，不展示不可操作的数据
  useEffect(() => {
    if (!storeId || !draftUserId) return
    const d = readDraft()
    if (!d) return
    if (!draftMatches(d, { userId: draftUserId, storeId })) {
      clearDraft()
      return
    }
    setPendingDraft(d)
  }, [storeId, draftUserId])

  const beginWatch = () => {
    stopWatch()
    watchRef.current = navigator.geolocation.watchPosition(
      (pos) => {
        const cur = recordingRef.current
        if (!cur || cur.pausedAt !== null) return
        const next = acceptPoint(cur, {
          lat: pos.coords.latitude,
          lng: pos.coords.longitude,
          atMillis: pos.timestamp,
          accuracyM: pos.coords.accuracy,
        })
        if (!next.accepted) return
        recordingRef.current = next.state
        setRecording(next.state)
        persistDraft(false)
      },
      (err) => {
        const msg =
          err.code === err.PERMISSION_DENIED
            ? '定位权限被拒绝：无法记录轨迹（签到/开始/完成不受影响，可继续履约）'
            : err.code === err.POSITION_UNAVAILABLE
              ? '当前无法获取定位（信号弱或设备不支持）'
              : '定位超时，请到开阔处重试'
        setGeoError(msg)
        push(msg, 'error')
        if (err.code === err.PERMISSION_DENIED) stopWatch()
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: 15_000 },
    )
  }

  const startTracking = (t: WalkingTask) => {
    if (recordingRef.current) {
      push('已有一条轨迹正在记录，请先结束或放弃后再开始', 'error')
      return
    }
    if (pendingDraft) {
      push('有未处理的轨迹草稿：先恢复或丢弃上方的草稿', 'error')
      return
    }
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      push('当前设备/浏览器不支持定位，无法记录轨迹（原有履约功能不受影响）', 'error')
      return
    }
    setGeoError(null)
    setTrackSaveError(null)
    setRestored(false)
    setNowTick(Date.now())
    const state = startRecording(t.appointmentId)
    recordingRef.current = state
    setRecording(state)
    beginWatch()
  }

  const pauseTracking = () => {
    if (!recordingRef.current) return
    stopWatch()
    const next = pauseRecording(recordingRef.current)
    recordingRef.current = next
    setRecording(next)
    persistDraft(true)
  }

  const resumeTracking = () => {
    if (!recordingRef.current) return
    const next = resumeRecording(recordingRef.current)
    recordingRef.current = next
    setRecording(next)
    setRestored(false)
    setNowTick(Date.now())
    beginWatch()
    persistDraft(true)
  }

  const discardTracking = () => {
    stopWatch()
    recordingRef.current = null
    setRecording(null)
    setGeoError(null)
    setTrackSaveError(null)
    setRestored(false)
    clearDraft()
    draftWrittenAtRef.current = 0
    push('已放弃本次轨迹（未保存任何数据）', 'success')
  }

  /** 从草稿恢复：只装载状态并停在"已暂停"，**不会自动开始采集**，用户点「继续记录」才 watch。 */
  const restorePendingDraft = () => {
    if (!pendingDraft || recordingRef.current) return
    const row = list.find((x) => x.appointmentId === pendingDraft.appointmentId)
    if (!row || row.track) return
    const state = draftToState(pendingDraft)
    recordingRef.current = state
    setRecording(state)
    setNowTick(Date.now())
    setRestored(true)
    setGeoError(null)
    setTrackSaveError(null)
    setPendingDraft(null)
    push('已恢复轨迹草稿（暂停中）：点「继续记录」后才会采集定位', 'success')
  }

  const discardPendingDraft = () => {
    clearDraft()
    draftWrittenAtRef.current = 0
    setPendingDraft(null)
    push('已丢弃未提交的轨迹草稿', 'success')
  }

  /**
   * 结束并保存：提交**原始坐标**，距离/时长/点数由服务端派生。
   * 单飞防止重复提交；失败保留缓冲（同一 trackId 可重试），成功后才清空并回队列拉真值。
   */
  const stopAndSaveTrack = async (t: WalkingTask) => {
    const cur = recordingRef.current
    if (!cur || trackBusyId) return
    if (cur.appointmentId !== t.appointmentId) return
    if (t.track) {
      // 服务端已有轨迹：草稿/缓冲一律不得覆盖（提交侧还有 uk_appt + 10005 兜底）
      push('该预约服务端已有轨迹，不能再提交', 'error')
      return
    }
    stopWatch()
    if (cur.points.length < 2) {
      // 数据不足：不提交、不伪造里程；停表并保留缓冲，允许用户继续走动后再试
      const paused = pauseRecording(cur)
      recordingRef.current = paused
      setRecording(paused)
      persistDraft(true)
      push('轨迹数据不足（有效定位点少于 2 个），未保存；可继续记录后重试', 'error')
      return
    }
    const pausedSec = finalPausedSec(cur)
    setTrackSaveError(null)
    setTrackBusyId(t.appointmentId)
    try {
      const saved = await walkingApi.saveTrack(t.appointmentId, {
        trackId: cur.trackId,
        pausedSec,
        points: cur.points,
      })
      push(`轨迹已保存：${formatDistanceM(saved.distanceM)} · ${formatDurationSec(saved.durationSec)}`, 'success')
      recordingRef.current = null
      setRecording(null)
      setGeoError(null)
      setRestored(false)
      setPendingDraft(null)
      clearDraft()
      draftWrittenAtRef.current = 0
      queue.reload()
    } catch (err) {
      // 保留缓冲与同一个 trackId（服务端幂等）+ 落草稿：失败可原地重试，刷新也还能恢复
      const paused = pauseRecording(cur)
      recordingRef.current = paused
      setRecording(paused)
      setTrackSaveError(err instanceof BizError ? err.message : '轨迹保存失败，请重试')
      persistDraft(true)
      push(err instanceof BizError ? err.message : '轨迹保存失败，请重试', 'error')
    } finally {
      setTrackBusyId(null)
    }
  }

  /** 传给行内轨道区的控制器（避免把一整套 setState 散进展示组件）。 */
  const trackCtl: TrackController = {
    recording,
    geoError,
    saveError: trackSaveError,
    restored,
    hasPendingDraft: pendingDraft !== null,
    busy: trackBusyId !== null,
    now: nowTick,
    onStart: startTracking,
    onPause: pauseTracking,
    onResume: resumeTracking,
    onStop: (t: WalkingTask) => void stopAndSaveTrack(t),
    onDiscard: discardTracking,
  }

  /** 草稿对应的当前行：不在当前日期/筛选内就只允许丢弃；服务端已有轨迹时禁止恢复。 */
  const draftRow = pendingDraft ? (list.find((x) => x.appointmentId === pendingDraft.appointmentId) ?? null) : null
  const draftBlockedByServer = !!draftRow?.track
  const draftRestorable = !!pendingDraft && !!draftRow && !draftBlockedByServer && !recording

  return (
    <Layout active="walking">
      <div className="grid gap-5 p-4 sm:p-6 2xl:grid-cols-[minmax(0,1fr)_320px]">
        <div className="flex min-w-0 flex-col gap-5">
          <div className="rounded-2xl bg-white p-5 shadow-soft ring-1 ring-line sm:p-6">
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div>
                <h1 className="text-[26px] font-black text-ink">遛狗服务</h1>
                <p className="mt-1.5 text-sm text-muted">户外遛狗（DOG_WALK）履约看板：签到、指派遛狗师、开始与完成记录</p>
                <p className="mt-1 text-[11px] text-muted">
                  数据源 <code className="font-mono">GET /store/walking/queue</code>（真实，ADR-049）·
                  类型判据 <code className="font-mono">cat_service.code = DOG_WALK</code>，不是服务名快照 ·
                  {day ? (day.storeWideScope ? ' 口径：全店当日队列（appointment:view:store）' : ' 口径：仅指派给本人的任务（服务端按 task:accept 收窄）') : ' 口径：加载中'}
                </p>
              </div>
              <div className="flex rounded-full bg-blue-mist p-1" role="tablist" aria-label="视图切换">
                {(['STORE', 'MINE'] as View[]).map((v) => (
                  <button
                    key={v}
                    type="button"
                    role="tab"
                    aria-selected={view === v}
                    onClick={() => {
                      setView(v)
                      setOpenId(null)
                    }}
                    className={cx('h-8 rounded-full px-4 text-xs font-bold transition', view === v ? 'bg-white text-blue shadow-soft' : 'text-muted')}
                  >
                    {v === 'STORE' ? '店看板' : '我的任务'}
                  </button>
                ))}
              </div>
            </div>

            {day && !day.storeWideScope && view === 'STORE' && (
              <p className="mt-4 flex items-start gap-2 rounded-xl bg-blue-mist/70 px-4 py-3 text-xs leading-relaxed text-muted ring-1 ring-line">
                <IconShield className="mt-0.5 h-4 w-4 shrink-0 text-blue" />
                <span>
                  当前账号只有 <code className="font-mono">task:accept</code>（无 <code className="font-mono">appointment:view:store</code>）：
                  「店看板」与「我的任务」是同一份服务端已收窄的结果，看不到别人的单属权限口径，不是加载失败。
                </span>
              </p>
            )}

            {day?.truncated && (
              <p className="mt-4 flex items-start gap-2 rounded-xl bg-[#FFF8E7] px-4 py-3 text-xs leading-relaxed text-[#8A6116] ring-1 ring-[#F3E0B5]">
                <IconBulb className="mt-0.5 h-4 w-4 shrink-0" />
                <span>当天门店预约超过后端 200 条上限，本队列与下面的计数都可能不完整。</span>
              </p>
            )}

            {pendingDraft && (
              <div className="mt-4 rounded-xl bg-[#FFF8E7] px-4 py-3 text-xs leading-relaxed text-[#8A6116] ring-1 ring-[#F3E0B5]">
                <p className="flex items-start gap-2">
                  <IconBulb className="mt-0.5 h-4 w-4 shrink-0" />
                  <span>
                    发现未提交的轨迹草稿：预约 #{pendingDraft.appointmentId}，{draftPointsText(pendingDraft)}，
                    最后更新 {dateTimeText(new Date(pendingDraft.savedAt).toISOString())}。
                    {draftBlockedByServer
                      ? ' 该预约服务端已有轨迹，草稿不能再提交，只能丢弃。'
                      : draftRow
                        ? ' 恢复后处于暂停状态，点「继续记录」才会采集定位。'
                        : ' 该预约不在当前日期/筛选内，切回对应日期后才能恢复。'}
                  </span>
                </p>
                <p className="mt-1 pl-6 text-[11px]">
                  草稿只存在本标签页的 sessionStorage：关标签页、隐私模式或浏览器清理后可能丢失，不能保证一定找回。
                </p>
                <div className="mt-2 flex flex-wrap gap-2 pl-6">
                  <button
                    type="button"
                    disabled={!draftRestorable}
                    onClick={restorePendingDraft}
                    className="h-8 rounded-lg bg-white px-3 text-xs font-semibold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    恢复草稿
                  </button>
                  <button
                    type="button"
                    onClick={discardPendingDraft}
                    className="h-8 rounded-lg bg-white px-3 text-xs font-semibold text-ink ring-1 ring-line transition hover:text-[#E8506B] hover:ring-[#E8506B]"
                  >
                    丢弃草稿
                  </button>
                </div>
              </div>
            )}

            <div className="mt-5 flex flex-wrap items-center gap-2.5">
              <div className="flex flex-wrap gap-2.5" role="tablist" aria-label="队列状态">
                {(['ALL', ...WALK_QUEUE_STATUSES] as Tab[]).map((key) => (
                  <button
                    key={key}
                    type="button"
                    role="tab"
                    aria-selected={tab === key}
                    onClick={() => {
                      setTab(key)
                      setOpenId(null)
                    }}
                    className={cx(
                      'h-9 rounded-full px-4 text-sm font-semibold transition',
                      tab === key ? 'bg-blue text-white shadow-soft' : 'bg-white text-ink ring-1 ring-line hover:text-blue',
                    )}
                  >
                    {key === 'ALL' ? '全部' : WALK_QUEUE_STATUS_LABEL[key]}
                    {counts && ` (${counts[key]})`}
                  </button>
                ))}
              </div>
              <label className="ml-auto flex items-center gap-2 text-xs text-muted">
                日期
                <input
                  type="date"
                  value={date}
                  onChange={(e) => {
                    setDate(e.target.value || dateOfOffset(0))
                    setOpenId(null)
                  }}
                  className="h-9 rounded-lg bg-white px-2.5 text-sm text-ink ring-1 ring-line focus:outline-none focus:ring-blue"
                />
              </label>
            </div>

            <div className="mt-5 flex flex-col gap-3">
              {recording && !list.some((x) => x.appointmentId === recording.appointmentId) && (
                <p className="flex flex-wrap items-center gap-2 rounded-xl bg-[#FFF8E7] px-4 py-3 text-xs leading-relaxed text-[#8A6116] ring-1 ring-[#F3E0B5]">
                  <IconBulb className="h-4 w-4 shrink-0" />
                  <span>
                    正在记录轨迹的预约不在当前日期/筛选内（已采集 {recording.points.length} 个点）：切回该预约所在日期后可暂停、结束并保存。
                  </span>
                  <button
                    type="button"
                    onClick={trackCtl.onDiscard}
                    className="ml-auto rounded-full bg-white px-3 py-1 text-[11px] font-bold text-ink ring-1 ring-line transition hover:text-[#E8506B] hover:ring-[#E8506B]"
                  >
                    放弃本次轨迹
                  </button>
                </p>
              )}
              {queue.block.state === 'loading' && <ListLoading />}
              {queue.block.state === 'error' && <ListError message={queue.block.message} onRetry={queue.reload} />}
              {day && shown.length === 0 && (
                <p className="rounded-2xl bg-blue-mist/60 py-12 text-center text-sm text-muted">
                  {day.date} 没有符合条件的遛狗任务
                  {list.length > 0
                    ? '（当天有别的遛狗单，是当前筛选把它们滤掉了）'
                    : mineOnly
                      ? '（切回「店看板」或换日期试试）'
                      : '（换日期试试；本店当天若没有顾客预约遛狗，这条队列本就是空的）'}
                </p>
              )}
              {shown.map((t) => (
                <WalkBlock
                  key={t.appointmentId}
                  task={t}
                  roster={rosterRows}
                  open={openId === t.appointmentId}
                  onToggle={() => setOpenId((v) => (v === t.appointmentId ? null : t.appointmentId))}
                  onCheckin={doCheckin}
                  onAssign={doAssign}
                  onStart={doStart}
                  onFinish={doFinish}
                  checking={checkingId === t.appointmentId}
                  busy={checkingId !== null || writingId !== null}
                  canAssign={day?.canAssign === true}
                  cancelRefund={refundByAppointment.get(t.appointmentId) ?? null}
                  canApprove={canApprove}
                  reviewingRefundId={reviewingId}
                  refundsDenied={refundsDenied}
                  refundsPartial={refundsPartial}
                  onReview={doReviewCancel}
                  photoBusy={photoBusyId === t.appointmentId}
                  onAddPhotos={doAddPhotos}
                  trackCtl={trackCtl}
                  canViewHealth={canViewHealth}
                  onOpenHealth={openHealth}
                />
              ))}
            </div>
          </div>
        </div>

        <div className="flex min-w-0 flex-col gap-5">
          <TodayCard date={day?.date ?? date} counts={counts} truncated={day?.truncated === true} loading={queue.block.state === 'loading'} />
          <StaffCard
            rows={staffRows}
            queueDate={day?.date ?? date}
            state={roster.block.state === 'ready' ? (day ? 'ready' : 'loading') : roster.block.state}
            storeWide={day?.storeWideScope ?? null}
            onRetry={() => {
              roster.reload()
              queue.reload()
            }}
          />
          <BoundaryCard />
        </div>
      </div>

      {healthPet && storeId && (
        <PetHealthDrawer
          pet={healthPet}
          storeId={storeId}
          canView={canViewHealth}
          canManage={false}
          onClose={() => setHealthPet(null)}
        />
      )}
    </Layout>
  )
}

/* ---- 区块数据（与洗澡页同模式：四态 + 陈旧响应丢弃） ---- */

type Block<T> = { state: 'loading' } | { state: 'denied' } | { state: 'error'; message: string } | { state: 'ready'; data: T }

function useAsync<T>(fetcher: () => Promise<T>, reloadKey: unknown): { block: Block<T>; reload: () => void } {
  const [block, setBlock] = useState<Block<T>>({ state: 'loading' })
  const [tick, setTick] = useState(0)
  const seq = useRef(0)
  const fetcherRef = useRef(fetcher)
  fetcherRef.current = fetcher

  useEffect(() => {
    if (reloadKey === null || reloadKey === undefined) return
    const my = ++seq.current
    setBlock({ state: 'loading' })
    fetcherRef.current().then(
      (data) => {
        if (my === seq.current) setBlock({ state: 'ready', data })
      },
      (err: unknown) => {
        if (my !== seq.current) return
        // 10003 是「这个视图不给看」，属权限口径不是故障：渲染成中性空态，别用红错卡吓人
        if (err instanceof BizError && err.code === 10003) setBlock({ state: 'denied' })
        else setBlock({ state: 'error', message: err instanceof BizError ? err.message : '加载失败，请重试' })
      },
    )
  }, [reloadKey, tick])

  return { block, reload: () => setTick((t) => t + 1) }
}

/* ---- 队列行 ---- */

function WalkBlock({
  task: t,
  roster,
  open,
  onToggle,
  onCheckin,
  onAssign,
  onStart,
  onFinish,
  checking,
  canAssign,
  busy,
  cancelRefund,
  canApprove,
  reviewingRefundId,
  refundsDenied,
  refundsPartial,
  onReview,
  photoBusy,
  onAddPhotos,
  trackCtl,
  canViewHealth,
  onOpenHealth,
}: {
  task: WalkingTask
  roster: EmployeeRow[]
  open: boolean
  onToggle: () => void
  onCheckin: (task: WalkingTask) => void
  onAssign: (task: WalkingTask, employeeId: string) => void
  onStart: (task: WalkingTask) => void
  onFinish: (task: WalkingTask, remark: string) => void
  canAssign: boolean
  checking: boolean
  busy: boolean
  /** 该预约的待审退款单（APPLYING）；null=未匹配到（已处理/无需退款/无权查看） */
  cancelRefund: RefundItem | null
  /** 调用者是否持 order:refund:approve（只决定按钮显隐，闸在服务端） */
  canApprove: boolean
  reviewingRefundId: string | null
  refundsDenied: boolean
  refundsPartial: boolean
  onReview: (task: WalkingTask, refund: RefundItem, kind: 'approve' | 'reject') => Promise<void>
  photoBusy: boolean
  onAddPhotos: (task: WalkingTask, files: File[]) => Promise<void>
  trackCtl: TrackController
  canViewHealth: boolean
  onOpenHealth: (task: WalkingTask) => void
}) {
  const pet = t.pet
  const url = mediaSrc(pet?.avatarUrl)
  // presigned GET 指向的对象可能不存在（签得出地址 ≠ 图在桶里），加载失败必须回落占位
  const [imgFailed, setImgFailed] = useState(false)
  const avatar = url && !imgFailed ? url : null
  return (
    <div className="overflow-hidden rounded-2xl bg-blue-mist/60 ring-1 ring-line">
      <button type="button" aria-expanded={open} onClick={onToggle} className="flex w-full items-center gap-4 p-4 text-left transition hover:bg-white/60">
        <span className="grid h-12 w-12 shrink-0 place-items-center overflow-hidden rounded-xl bg-white text-blue ring-1 ring-line">
          {avatar ? <img src={avatar} alt={pet?.name ?? '宠物头像'} className="h-full w-full object-cover" onError={() => setImgFailed(true)} /> : <IconDog className="h-6 w-6" />}
        </span>
        <div className="min-w-0 flex-1">
          <p className="flex flex-wrap items-center gap-2 text-base font-black text-ink">
            <span className="truncate">{pet?.name ?? '宠物档案已删除'}</span>
            <span className="rounded-full bg-white px-2 py-0.5 text-[11px] font-semibold text-muted ring-1 ring-line">
              {pet ? `${speciesText(pet.species)} · ${pet.breed ?? '品种未登记'}` : '无宠物信息'}
            </span>
            <Badge label={WALK_QUEUE_STATUS_LABEL[t.status] ?? t.status} tone={statusTone(t.status)} />
            {isFutureDate(t.serviceDate) && t.status !== 'COMPLETED' && <Badge label="未到日期" tone="orange" />}
            {t.assignedToMe && <Badge label="我的任务" tone="green" />}
          </p>
          <p className="mt-1 truncate text-sm text-muted">
            {t.serviceName ?? '服务名未记录'} · {walkTimeText(t)} · {durationText(t.durationMin)} · {t.price == null ? '价格未记录' : fenToYuanText(t.price)}
          </p>
          <p className="mt-0.5 flex flex-wrap items-center gap-x-3 text-[11px] text-muted">
            <span>遛狗师：{employeeText(t, roster)}</span>
            <span>履约记录：{t.record ? `已提交（${dateTimeText(t.record.finishedAt)}）` : '未提交'}</span>
          </p>
        </div>
        <IconChevronDown className={cx('h-4 w-4 shrink-0 text-muted transition-transform', open && 'rotate-180')} />
      </button>
      {open && (
        <WalkDetail
          task={t}
          roster={roster}
          onCheckin={onCheckin}
          onAssign={onAssign}
          onStart={onStart}
          onFinish={onFinish}
          checking={checking}
          busy={busy}
          canAssign={canAssign}
          cancelRefund={cancelRefund}
          canApprove={canApprove}
          reviewing={!!cancelRefund && reviewingRefundId === cancelRefund.id}
          refundsDenied={refundsDenied}
          refundsPartial={refundsPartial}
          onReview={onReview}
          photoBusy={photoBusy}
          onAddPhotos={onAddPhotos}
          trackCtl={trackCtl}
          canViewHealth={canViewHealth}
          onOpenHealth={onOpenHealth}
        />
      )}
    </div>
  )
}

function speciesText(species: string | null): string {
  if (species === 'DOG') return '犬'
  if (species === 'CAT') return '猫'
  return species ?? '未知'
}

/** 队列行只给执行人 id + 名字，角色在本店名册里；拿不到名册就只报名字，不编角色。 */
function employeeText(t: WalkingTask, roster: EmployeeRow[]): string {
  if (!t.employee) return '未指派'
  const roles = roster.find((e) => e.employeeId === t.employee?.id)?.roleCodes ?? []
  return roles.length ? `${t.employee.name}（${roles.map((c) => ROLE_LABEL[c] ?? c).join('/')}）` : t.employee.name
}

function statusTone(s: WalkQueueStatus): Tone {
  if (s === 'COMPLETED') return 'green'
  if (s === 'SERVING') return 'orange'
  if (s === 'WAIT_SERVICE') return 'blue'
  return 'gray'
}

/** 无调度权限时替代指派控件的诚实说明（ADR-054）：员工不可互派任务，指派是店长/前台的职能。 */
function canAssignSectionBlocked() {
  return (
    <div className="rounded-xl bg-warmgray/60 p-3">
      <p className="flex items-center gap-1.5 text-xs font-bold text-muted">
        <IconUser className="h-3.5 w-3.5 text-blue" /> 指派需调度权限
      </p>
      <p className="mt-1.5 text-[11px] leading-relaxed text-muted">
        指派/改派是店长与前台的职能（appointment:assign/reassign）：员工不可互派任务。
        这单的执行安排请联系店长；你自己已被指派的单会出现在「我的任务」里。
      </p>
    </div>
  )
}

function WalkDetail({
  task: t,
  roster,
  onCheckin,
  onAssign,
  onStart,
  onFinish,
  checking,
  busy,
  canAssign: callerCanAssign,
  cancelRefund,
  canApprove,
  reviewing,
  refundsDenied,
  refundsPartial,
  onReview,
  photoBusy,
  onAddPhotos,
  trackCtl,
  canViewHealth,
  onOpenHealth,
}: {
  task: WalkingTask
  roster: EmployeeRow[]
  onCheckin: (task: WalkingTask) => void
  onAssign: (task: WalkingTask, employeeId: string) => void
  onStart: (task: WalkingTask) => void
  onFinish: (task: WalkingTask, remark: string) => void
  checking: boolean
  busy: boolean
  canAssign: boolean
  cancelRefund: RefundItem | null
  canApprove: boolean
  reviewing: boolean
  refundsDenied: boolean
  refundsPartial: boolean
  onReview: (task: WalkingTask, refund: RefundItem, kind: 'approve' | 'reject') => Promise<void>
  photoBusy: boolean
  onAddPhotos: (task: WalkingTask, files: File[]) => Promise<void>
  trackCtl: TrackController
  canViewHealth: boolean
  onOpenHealth: (task: WalkingTask) => void
}) {
  const [remark, setRemark] = useState(t.record?.remark ?? '')
  // 指派候选：在职 + 今日有班。技能/时段冲突不在这儿筛——那是后端的判定，前端筛了只会两处口径打架
  const [pick, setPick] = useState('')
  const assignable = roster.filter((e) => e.status !== 'RESIGNED' && e.todayShift?.status === 'WORKING')
  // ADR-054：指派是调度职能——服务端 canAssign（持 assign/reassign）才渲染指派控件，员工看到的是说明而非假控件
  const canAssign = callerCanAssign && (t.status === 'CONFIRMED' || t.status === 'WAIT_SERVICE')
  const pet = t.pet
  const rec = t.record

  return (
    <div className="flex flex-col gap-4 border-t border-line bg-white p-4">
      <div className="grid gap-x-6 gap-y-2.5 sm:grid-cols-2 xl:grid-cols-3">
        <Field label="预约号" value={t.appointmentNo} mono />
        <Field label="服务日期" value={t.serviceDate} />
        <Field label="时段" value={walkTimeText(t)} />
        <Field label="体型" value={pet?.sizeCategory ?? '未登记'} />
        <Field label="性别" value={pet?.gender === 'MALE' ? '公' : pet?.gender === 'FEMALE' ? '母' : '未登记'} />
        <Field label="下单来源" value={t.source === 'RECEPTIONIST' ? '前台代录' : t.source === 'CUSTOMER' ? '顾客自约' : '未记录'} />
        <Field label="实际开始" value={rec ? dateTimeText(rec.startedAt) : '未开始'} />
        <Field label="实际完成" value={rec ? dateTimeText(rec.finishedAt) : '未完成'} />
        <Field label="实际时长" value={rec ? (rec.durationMin != null ? `${rec.durationMin} 分钟` : '未记录') : '—'} />
      </div>

      {pet && canViewHealth && (
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl bg-blue-mist/60 px-3.5 py-3 ring-1 ring-line">
          <div className="min-w-0">
            <p className="flex items-center gap-1.5 text-xs font-bold text-ink">
              <IconHeartPulse className="h-3.5 w-3.5 text-blue" /> 宠物健康档案
            </p>
            <p className="mt-0.5 text-[11px] leading-relaxed text-muted">
              只读查看过敏、慢性病与特殊备注；编辑仍在「宠物管理」。
            </p>
          </div>
          <button
            type="button"
            onClick={() => onOpenHealth(t)}
            className="inline-flex h-8 shrink-0 items-center gap-1 rounded-lg bg-white px-3 text-xs font-semibold text-ink ring-1 ring-line transition hover:border-blue hover:text-blue focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue/40"
          >
            查看健康档案
          </button>
        </div>
      )}

      {t.status === 'CANCEL_REQUESTED' && (
        <CancelRequestSection
          task={t}
          refund={cancelRefund}
          canApprove={canApprove}
          reviewing={reviewing}
          refundsDenied={refundsDenied}
          refundsPartial={refundsPartial}
          onReview={onReview}
        />
      )}

      {t.status === 'COMPLETED' && !rec && (
        <p className="flex items-start gap-2 rounded-xl bg-[#FFF8E7] px-4 py-3 text-xs leading-relaxed text-[#8A6116] ring-1 ring-[#F3E0B5]">
          <IconBulb className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            状态已是「已完成」，但 <code className="font-mono">ful_walk_record</code> 里没有对应记录——该单完成于履约链上线前
            （或记录被软删），此处如实显示「未提交」，不补一条假记录。
          </span>
        </p>
      )}

      {rec && (
        <section className="rounded-xl bg-blue-mist/60 p-3 ring-1 ring-line">
          <p className="mb-2 flex items-center gap-1.5 text-xs font-bold text-muted">
            <IconCheckCircle className="h-3.5 w-3.5 text-[#2FA96B]" /> 遛狗记录
            <span className="font-normal">· {roster.find((e) => e.employeeId === rec.employeeId)?.name ?? (rec.employeeId ? `员工 #${rec.employeeId}` : '执行人未记录')}</span>
            <span className="font-normal">· 完成于 {rec.finishedAt ? dateTimeText(rec.finishedAt) : '未记录'}</span>
            {rec.byManager && <Badge label="店长代点" tone="orange" />}
          </p>
          <p className="text-xs text-muted">
            实际遛了 {rec.durationMin != null ? `${rec.durationMin} 分钟` : '时长未记录'}（起止实算，不是预约快照时长）
          </p>
          <p className="mt-2 text-xs text-muted">{rec.remark || '无备注'}</p>
          <p className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted">
            <span>GPS：{t.track ? trackSummaryText(t.track) : '未记录 GPS 轨迹'}</span>
            <span>照片：{recordPhotoCount(rec)} 张</span>
          </p>
          <p className="mt-1 text-[11px] leading-relaxed text-muted">
            这里只汇总服务端已保存的履约记录、轨迹与照片；录制中尚未提交的 GPS 缓冲不计入完成报告。
          </p>
        </section>
      )}

      {rec && (
        <WalkPhotosSection
          record={rec}
          busy={photoBusy}
          onAddPhotos={(files) => void onAddPhotos(t, files)}
        />
      )}

      {(t.status === 'SERVING' || t.status === 'COMPLETED') && <WalkTrackSection task={t} ctl={trackCtl} />}

      {t.status === 'SERVING' && (
        <section>
          <p className="mb-2 flex items-center gap-1.5 text-xs font-bold text-muted">
            <IconDog className="h-3.5 w-3.5 text-blue" /> 完成遛狗并写记录
          </p>
          <textarea
            value={remark}
            onChange={(e) => setRemark(e.target.value)}
            rows={2}
            maxLength={200}
            placeholder="备注（可选，≤200 字）：精神状态、排泄情况、需告知主人的事项"
            className="w-full rounded-xl bg-white px-3 py-2 text-sm text-ink ring-1 ring-line placeholder:text-muted focus:outline-none focus:ring-blue"
          />
          <p className="mt-2 text-[11px] leading-relaxed text-muted">
            完成动作只写 <code className="font-mono">ful_walk_record</code> 的备注，起止时间与实际时长由系统按点击实算；
            照片在完成后单独关联，GPS 轨迹由独立的录制/保存入口写入，均不在此表单里。
          </p>
        </section>
      )}

      {canAssign ? (
        <section className="rounded-xl bg-warmgray/60 p-3">
          <p className="mb-2 flex items-center gap-1.5 text-xs font-bold text-muted">
            <IconUser className="h-3.5 w-3.5 text-blue" /> {t.employee ? '改派遛狗师' : '指派遛狗师'}
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <select
              aria-label="选择遛狗师"
              value={pick}
              onChange={(e) => setPick(e.target.value)}
              className="h-9 min-w-[12rem] rounded-xl bg-white px-2 text-sm text-ink ring-1 ring-line focus:outline-none focus:ring-blue"
            >
              <option value="">{assignable.length ? '选择本店今日有班的员工' : '本店今日无人排班：先到【员工排班】排班，再来指派'}</option>
              {assignable
                .filter((e) => e.employeeId !== t.employee?.id)
                .map((e) => (
                  <option key={e.employeeId} value={e.employeeId}>
                    {e.name}（{(e.roleCodes ?? []).map((c) => ROLE_LABEL[c] ?? c).join('/') || '无角色'}）
                  </option>
                ))}
            </select>
            <ActionButton
              Icon={IconUser}
              label={busy ? '提交中…' : t.employee ? '确认改派' : '确认指派'}
              onClick={() => onAssign(t, pick)}
              disabled={!pick || pick === t.employee?.id}
              busy={busy}
            />
          </div>
          <p className="mt-2 text-[11px] leading-relaxed text-muted">
            后端会校验该员工在本店在职、当日班次覆盖本单时段、技能覆盖遛狗（WALK）、且这段时间没有别的单
            （40001/40002/40003 会原样回显）。已开始服务的单不能再改派；**指派是调度职能，员工不可互派任务**。
          </p>
        </section>
      ) : (
        canAssignSectionBlocked()
      )}

      {isFutureDate(t.serviceDate) && t.status !== 'COMPLETED' && (
        <p className="flex items-center gap-1.5 rounded-lg bg-[#FFF8E7] px-3 py-2 text-[11px] leading-relaxed text-[#8A6116]">
          <IconBulb className="h-3.5 w-3.5 shrink-0" />
          该单预约日期是 {t.serviceDate}，到当天可开始服务
        </p>
      )}

      <div className="flex flex-wrap gap-2.5 border-t border-line pt-3">
        {t.status === 'CONFIRMED' && (
          <ActionButton
            Icon={IconCheckCircle}
            label={checking ? '签到中…' : '到店签到'}
            onClick={() => onCheckin(t)}
            disabled={busy}
            busy={checking}
          />
        )}
        {t.status === 'WAIT_SERVICE' && t.employee && (
          <ActionButton Icon={IconClock} label="开始遛狗" onClick={() => onStart(t)} disabled={busy} busy={busy} />
        )}
        {t.status === 'WAIT_SERVICE' && !t.employee && (
          <p className="flex items-center gap-1.5 text-[11px] leading-relaxed text-muted">
            <IconClock className="h-3.5 w-3.5 shrink-0" />
            未指派执行人：先在上方指派，才能开始遛狗（完成记录要挂执行人，无执行人的单开始会被 50003 拒）
          </p>
        )}
        {t.status === 'SERVING' && (
          <ActionButton
            Icon={IconCheckCircle}
            label="完成并提交记录"
            onClick={() => onFinish(t, remark)}
            disabled={busy}
            busy={busy}
          />
        )}
      </div>
    </div>
  )
}

/**
 * 取消审核区（仅 CANCEL_REQUESTED 行）。申请信息全部取自真实退款单
 * （金额/理由/申请时间/退款单号/申请人），缺哪项就如实说哪项缺，不从预约字段推断。
 * 操作只在"有可审退款单 + 持 order:refund:approve"时出现；同意/拒绝都必须先过二次确认。
 */
function CancelRequestSection({
  task,
  refund,
  canApprove,
  reviewing,
  refundsDenied,
  refundsPartial,
  onReview,
}: {
  task: WalkingTask
  refund: RefundItem | null
  canApprove: boolean
  reviewing: boolean
  refundsDenied: boolean
  refundsPartial: boolean
  onReview: (task: WalkingTask, refund: RefundItem, kind: 'approve' | 'reject', rejectReason?: string) => Promise<void>
}) {
  const [confirm, setConfirm] = useState<'approve' | 'reject' | null>(null)
  const [rejectReason, setRejectReason] = useState('')
  const [rejectError, setRejectError] = useState('')
  const reviewable = canReviewCancel(task, refund, canApprove)
  const amountText = refund ? fenToYuanText(refund.amount) : null

  const submit = () => {
    if (!refund || !confirm) return
    const kind = confirm
    if (kind === 'reject') {
      const reason = rejectReason.trim()
      if (!reason) {
        setRejectError('请填写拒绝理由，顾客会在退款进度中看到')
        return
      }
      if (reason.length > 200) {
        setRejectError('拒绝理由不能超过 200 字')
        return
      }
    }
    // 先收起确认态：失败时回到按钮态，由 toast 说明原因；这里不改任何状态文案
    setConfirm(null)
    setRejectReason('')
    setRejectError('')
    void onReview(task, refund, kind, rejectReason.trim() || undefined)
  }

  return (
    <section className="rounded-xl bg-[#FFF1F4] p-3 ring-1 ring-[#F3C8D2]">
      <p className="flex flex-wrap items-center gap-2 text-xs font-bold text-ink">
        <IconShield className="h-3.5 w-3.5 text-[#E8506B]" /> 客户取消申请
        {refund ? (
          <Badge label={REFUND_STATUS_LABEL[refund.status] ?? refund.status} tone="pink" />
        ) : (
          <Badge label="未匹配到待处理退款单" tone="orange" />
        )}
      </p>

      {refund ? (
        <div className="mt-2 grid gap-x-6 gap-y-1.5 text-xs text-muted sm:grid-cols-2">
          <p>
            退款单号 <span className="font-mono text-ink">{refund.refundNo}</span>
          </p>
          <p>
            申请金额 <span className="font-semibold text-ink">{amountText}</span>
          </p>
          <p>
            申请时间 <span className="text-ink">{orderTimeText(refund.createdAt)}</span>
          </p>
          <p>
            申请人 <span className="text-ink">{applyByText(refund.applyBy)}</span>
          </p>
          <p className="sm:col-span-2">
            客户理由：<span className="text-ink">{refund.reason || '（未填写）'}</span>
          </p>
        </div>
      ) : (
        <p className="mt-2 text-xs leading-relaxed text-muted">
          {refundsDenied
            ? '当前账号没有 order:refund:view，看不到这笔取消申请的退款详情（申请信息只在订单管理可见）。'
            : '待处理退款列表里没有匹配到该预约的退款单：可能刚被同事处理完，或该单进入取消审核时未产生退款。请以订单管理的退款单为准。'}
          <Link to="/orders?tab=REFUNDING" className="ml-1 font-semibold text-blue hover:underline">
            去订单管理
          </Link>
        </p>
      )}

      {refund && !canApprove && (
        <p className="mt-2 rounded-lg bg-white px-3 py-2 text-[11px] leading-relaxed text-muted">
          你可以查看申请与进度；<b className="text-ink">同意/拒绝由店长、前台或平台管理员处理</b>（复用订单管理同一审批链）。
          <Link to="/orders?tab=REFUNDING" className="ml-1 font-semibold text-blue hover:underline">
            去订单管理
          </Link>
        </p>
      )}

      {reviewable && (
        <div className="mt-3 border-t border-[#F3C8D2] pt-2.5">
          <p className="text-[11px] leading-relaxed text-muted">
            同意＝按 ADR-043 记退款成功（当前为模拟执行）并取消预约；拒绝＝退款单置拒绝、预约恢复「
            {WALK_QUEUE_STATUS_LABEL.CONFIRMED}」。店长单笔超平台上限时由服务端拒绝（60006）。
          </p>
          {confirm === null ? (
            <div className="mt-2 flex flex-wrap gap-2">
              <button
                type="button"
                disabled={reviewing}
                onClick={() => setConfirm('reject')}
                className="h-9 rounded-lg bg-white px-3 text-xs font-semibold text-ink ring-1 ring-line transition hover:text-[#E8506B] hover:ring-[#E8506B] disabled:cursor-not-allowed disabled:opacity-50"
              >
                {reviewing ? '处理中…' : '拒绝申请'}
              </button>
              <button
                type="button"
                disabled={reviewing}
                onClick={() => setConfirm('approve')}
                className="h-9 rounded-lg bg-[#2E6BFF] px-3 text-xs font-bold text-white transition hover:bg-[#1E5AE8] disabled:cursor-not-allowed disabled:opacity-50"
              >
                {reviewing ? '处理中…' : '同意取消并退款'}
              </button>
            </div>
          ) : (
            <div className="mt-2 rounded-lg bg-white p-3 ring-1 ring-line">
              <p className="text-xs font-bold text-ink">
                {confirm === 'approve' ? `确认同意取消并退款 ${amountText}？` : '确认拒绝这笔取消申请？'}
              </p>
              <p className="mt-1 text-[11px] leading-relaxed text-muted">
                {confirm === 'approve'
                  ? '退款单将记为已退款（模拟执行），预约随后取消并释放占用；此操作不可撤销。'
                  : `退款单将置为已拒绝，预约恢复「${WALK_QUEUE_STATUS_LABEL.CONFIRMED}」后可继续履约。`}
              </p>
              {confirm === 'reject' && (
                <div className="mt-2">
                  <label className="block text-[11px] font-semibold text-ink" htmlFor={refund ? `reject-reason-${refund.id}` : 'reject-reason'}>
                    拒绝理由（必填，顾客可见）
                  </label>
                  <textarea
                    id={refund ? `reject-reason-${refund.id}` : 'reject-reason'}
                    value={rejectReason}
                    onChange={(e) => {
                      setRejectReason(e.target.value)
                      if (rejectError) setRejectError('')
                    }}
                    rows={2}
                    maxLength={200}
                    placeholder="例如：该时段已安排其他照护，建议改约"
                    className="mt-1 w-full rounded-lg bg-blue-mist/60 px-2.5 py-1.5 text-xs text-ink ring-1 ring-line outline-none transition placeholder:text-muted/60 focus:ring-blue"
                  />
                  {rejectError && <p className="mt-1 text-[11px] font-semibold text-[#E8506B]">{rejectError}</p>}
                </div>
              )}
              <div className="mt-2 flex gap-2">
                <button
                  type="button"
                  disabled={reviewing}
                  onClick={() => setConfirm(null)}
                  className="h-8 rounded-lg bg-white px-3 text-xs font-semibold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue disabled:opacity-50"
                >
                  取消
                </button>
                <button
                  type="button"
                  disabled={reviewing}
                  onClick={submit}
                  className="h-8 rounded-lg px-3 text-xs font-bold text-white transition disabled:opacity-50"
                  style={{ background: confirm === 'approve' ? '#2E6BFF' : '#E8506B' }}
                >
                  {reviewing ? '处理中…' : confirm === 'approve' ? '确认同意' : '确认拒绝'}
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {refundsPartial && (
        <p className="mt-2 text-[11px] leading-relaxed text-[#8A6116]">
          待处理退款按 100 条一页读取，超过时个别申请可能未匹配到；可在订单管理查看全量。
        </p>
      )}
    </section>
  )
}

/** 退款单申请人：applyBy 是 USER/STORE/ADMIN/SYSTEM（出参不含客户身份，ADR-017 口径）。 */
function applyByText(applyBy: string | null): string {
  switch (applyBy) {
    case 'USER':
      return '顾客本人'
    case 'STORE':
      return '门店代提'
    case 'ADMIN':
      return '平台'
    case 'SYSTEM':
      return '系统'
    default:
      return applyBy || '未记录'
  }
}

/**
 * 遛狗照片区（仅"已完成且有履约记录"的行）。
 *
 * <p>照片在库里只有对象 key；这里渲染的是服务端现签的 600s GET URL，不把临时地址当永久字段。
 * 上传是"先直传对象存储拿 key、再一次性关联到记录"两步：失败只提示、不刷新、不把本地文件先塞进列表——
 * 页面显示的一直是服务端真值。入口在服务端按 `task:finish` + 门店/执行人范围复核，前端不猜权限。
 */
function WalkPhotosSection({
  record,
  busy,
  onAddPhotos,
}: {
  record: WalkRecordRef
  busy: boolean
  onAddPhotos: (files: File[]) => void
}) {
  const urls = recordPhotoUrls(record)
  const count = recordPhotoCount(record)
  const atCap = remainingPhotoSlots(count) <= 0
  return (
    <section className="rounded-xl bg-blue-mist/60 p-3 ring-1 ring-line">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="flex items-center gap-1.5 text-xs font-bold text-muted">
          <IconImage className="h-3.5 w-3.5 text-blue" /> 遛狗照片
          <span className="font-normal">
            {count > 0 ? `${count} 张（上限 ${MAX_WALK_PHOTOS}）` : '暂无'}
          </span>
        </p>
        {!atCap && (
          <label
            className={cx(
              'inline-flex h-8 cursor-pointer items-center gap-1 rounded-lg bg-white px-3 text-xs font-semibold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue',
              busy && 'pointer-events-none opacity-60',
            )}
          >
            <IconPlus className="h-3.5 w-3.5" />
            {busy ? '处理中…' : '添加照片'}
            <input
              type="file"
              accept="image/jpeg,image/png,image/webp"
              multiple
              className="hidden"
              disabled={busy}
              onChange={(e) => {
                const files = Array.from(e.currentTarget.files ?? [])
                // 清空 value：同一批文件再选一次也要能触发 change
                e.currentTarget.value = ''
                if (files.length) onAddPhotos(files)
              }}
            />
          </label>
        )}
      </div>

      {count === 0 ? (
        <p className="mt-2 text-[11px] leading-relaxed text-muted">
          该记录还没有照片（历史记录同样如此，不补假图）。照片会展示给宠物主人，只放适合顾客看的画面。
        </p>
      ) : urls.length === 0 ? (
        <p className="mt-2 text-[11px] leading-relaxed text-[#8A6116]">
          已有 {count} 张照片，但当前取不到读取地址（对象存储未启用或签名失败）；记录本身不受影响，可稍后刷新重试。
        </p>
      ) : (
        <ul className="mt-2 flex flex-wrap gap-2">
          {urls.map((url, i) => (
            <li key={`${url}-${i}`}>
              <PhotoThumb url={url} index={i} />
            </li>
          ))}
        </ul>
      )}

      {atCap && (
        <p className="mt-2 text-[11px] text-muted">
          已达单条记录 {MAX_WALK_PHOTOS} 张上限（本轮不提供删除/替换）。
        </p>
      )}
    </section>
  )
}

/** 缩略图：加载失败回落占位，不影响记录其他信息（presigned URL 过期/对象被删都会走到这里）。 */
function PhotoThumb({ url, index }: { url: string; index: number }) {
  const [failed, setFailed] = useState(false)
  if (failed) {
    return (
      <span
        className="grid h-16 w-16 place-items-center rounded-lg bg-white text-[10px] text-muted ring-1 ring-line"
        title="图片加载失败"
      >
        加载失败
      </span>
    )
  }
  return (
    <a href={url} target="_blank" rel="noreferrer" title={`第 ${index + 1} 张，点击查看原图`}>
      <img
        src={url}
        alt={`遛狗照片 ${index + 1}`}
        loading="lazy"
        onError={() => setFailed(true)}
        className="h-16 w-16 rounded-lg object-cover ring-1 ring-line transition hover:ring-blue"
      />
    </a>
  )
}

/**
 * GPS 轨迹区（服务中可采集、完成后可查看历史摘要）。
 *
 * <p>隐私边界：只有用户点「开始轨迹记录」并在浏览器授权后采集；暂停即停止采集；
 * 离开页面/切店立即 clearWatch（不做后台持续定位）；数据只挂在本条预约上。
 * 距离/时长/点数是**保存后服务端回显的持久化值**，录制中的"已记录"仅为实时预览。
 */
function WalkTrackSection({ task, ctl }: { task: WalkingTask; ctl: TrackController }) {
  const saved = task.track
  const mine = ctl.recording && ctl.recording.appointmentId === task.appointmentId ? ctl.recording : null
  const otherRecording = !!ctl.recording && ctl.recording.appointmentId !== task.appointmentId
  const state = trackUiState({
    hasSavedTrack: !!saved,
    recording: mine,
    geoError: ctl.geoError,
    saving: ctl.busy,
    saveError: ctl.saveError,
  })
  const liveSec = mine ? recordingDurationSec(mine, ctl.now) : 0
  const paused = mine?.pausedAt != null
  const acc = mine ? lastAccuracyM(mine.points) : null
  const poor = isPoorAccuracy(acc)
  const startBlocked = otherRecording || ctl.hasPendingDraft
  const stateTone: Tone =
    state === 'saved' || state === 'recording'
      ? 'green'
      : state === 'paused' || state === 'geo-unavailable'
        ? 'orange'
        : state === 'save-failed'
          ? 'pink'
          : state === 'saving'
            ? 'blue'
            : 'gray'

  return (
    <section className="rounded-xl bg-blue-mist/60 p-3 ring-1 ring-line">
      <p className="flex flex-wrap items-center gap-2 text-xs font-bold text-muted">
        <IconMapPin className="h-3.5 w-3.5 text-blue" /> GPS 轨迹
        <Badge label={TRACK_UI_LABEL[state]} tone={stateTone} />
        {saved && <span className="font-normal">已保存（不可修改）</span>}
      </p>

      {saved ? (
        <div className="mt-2 flex flex-wrap items-start gap-3">
          <TrackSparkline polyline={saved.polyline} />
          <div className="min-w-0 flex-1 text-xs leading-relaxed text-muted">
            <p className="text-ink">{trackSummaryText(saved)}</p>
            <p className="mt-0.5 text-[11px]">
              采集区间 {dateTimeText(saved.startedAt)} → {dateTimeText(saved.endedAt)}
              {saved.pausedSec > 0 ? `（暂停 ${formatDurationSec(saved.pausedSec)}，已从记录时长中扣除）` : ''}
            </p>
            <p className="mt-0.5 text-[11px]">
              距离由服务端按轨迹点累加（已剔除跳点/重复点，并按定位精度门控），不是预约时长或估算值。
            </p>
          </div>
        </div>
      ) : mine ? (
        <div className="mt-2 text-xs leading-relaxed text-muted">
          <p className="text-ink">
            已记录 {formatDurationSec(liveSec)} · 已采集 {mine.points.length} 个定位点
            {acc != null ? ` · ${accuracyText(acc)}` : ''}
          </p>
          <p className="mt-0.5 text-[11px]">
            {ctl.restored
              ? '已从草稿恢复（暂停中）：点「继续记录」才会重新采集定位。'
              : paused
                ? '暂停中：不采集新点，暂停时长不计入记录时长。'
                : ctl.geoError
                  ? '暂时拿不到定位：不会增加里程，恢复后可继续采集。'
                  : '保持页面在前台；离开页面或切店会停止采集。'}
          </p>
          {poor && (
            <p className="mt-0.5 text-[11px] text-[#8A6116]">
              定位精度较差（约 {Math.round(acc ?? 0)} 米）：这一时段的位移可能小于误差，服务端不会计入里程。
            </p>
          )}
          {ctl.geoError && <p className="mt-1 text-[11px] text-[#E8506B]">{ctl.geoError}</p>}
          {ctl.saveError && (
            <p className="mt-1 text-[11px] text-[#E8506B]">
              保存失败：{ctl.saveError}（数据与 trackId 已保留，可直接重试；不会产生第二条轨迹）
            </p>
          )}
          <div className="mt-2 flex flex-wrap gap-2">
            {paused ? (
              <button
                type="button"
                onClick={ctl.onResume}
                className="h-8 rounded-lg bg-white px-3 text-xs font-semibold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue"
              >
                {ctl.geoError ? '重试定位' : '继续记录'}
              </button>
            ) : (
              <button
                type="button"
                onClick={ctl.onPause}
                className="h-8 rounded-lg bg-white px-3 text-xs font-semibold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue"
              >
                暂停
              </button>
            )}
            <button
              type="button"
              onClick={() => ctl.onStop(task)}
              disabled={ctl.busy}
              className="h-8 rounded-lg bg-[#2E6BFF] px-3 text-xs font-bold text-white transition hover:bg-[#1E5AE8] disabled:opacity-50"
            >
              {ctl.busy ? '保存中…' : ctl.saveError ? '重试保存' : '结束并保存'}
            </button>
            <button
              type="button"
              onClick={ctl.onDiscard}
              disabled={ctl.busy}
              className="h-8 rounded-lg bg-white px-3 text-xs font-semibold text-ink ring-1 ring-line transition hover:text-[#E8506B] hover:ring-[#E8506B] disabled:opacity-50"
            >
              放弃
            </button>
          </div>
        </div>
      ) : task.status === 'SERVING' ? (
        <div className="mt-2 text-xs leading-relaxed text-muted">
          <p>
            点击后浏览器会请求定位授权；授权后开始采集真实坐标，距离由服务端按轨迹点与定位精度计算，
            前端不上传"算好的里程"。
          </p>
          {ctl.geoError && <p className="mt-1 text-[11px] text-[#E8506B]">{ctl.geoError}</p>}
          <button
            type="button"
            onClick={() => ctl.onStart(task)}
            disabled={startBlocked}
            className="mt-2 inline-flex h-8 items-center gap-1 rounded-lg bg-white px-3 text-xs font-semibold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue disabled:cursor-not-allowed disabled:opacity-50"
          >
            <IconMapPin className="h-3.5 w-3.5" /> 开始轨迹记录
          </button>
          {otherRecording && (
            <p className="mt-1 text-[11px] text-[#8A6116]">另一个任务正在记录轨迹，请先结束或放弃后再开始。</p>
          )}
          {ctl.hasPendingDraft && (
            <p className="mt-1 text-[11px] text-[#8A6116]">有未处理的轨迹草稿：先恢复或丢弃上方的草稿。</p>
          )}
        </div>
      ) : (
        <p className="mt-2 text-[11px] leading-relaxed text-muted">
          本次未记录 GPS 轨迹（只有用户主动开始并授权定位才有数据；历史记录不补造轨迹）。
        </p>
      )}
    </section>
  )
}

/** 内联 SVG 示意路线：项目未接地图 SDK，不引依赖/密钥；少于 2 点显示"无轨迹线"而不画假路线。 */
function TrackSparkline({ polyline }: { polyline: [number, number][] | null }) {
  const d = trackSvgPath(polyline)
  if (!d) {
    return (
      <span className="grid h-12 w-28 shrink-0 place-items-center rounded-lg bg-white text-[10px] text-muted ring-1 ring-line">
        无轨迹线
      </span>
    )
  }
  return (
    <svg
      viewBox="0 0 120 48"
      width={120}
      height={48}
      role="img"
      aria-label="轨迹示意路线"
      className="shrink-0 rounded-lg bg-white ring-1 ring-line"
    >
      <path d={d} fill="none" stroke="#2E6BFF" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

/* ---- 右栏 ---- */

function TodayCard({
  date,
  counts,
  truncated,
  loading,
}: {
  /** 上方所选业务日（不是"今天"）：卡片标题必须跟着它走，避免"今日"文案悄悄换口径 */
  date: string
  counts: Record<'ALL' | WalkQueueStatus, number> | null
  truncated: boolean
  loading: boolean
}) {
  const boxes: [string, number | null][] = [
    ['全部任务', counts?.ALL ?? null],
    ['待签到', counts?.CONFIRMED ?? null],
    ['取消审核中', counts?.CANCEL_REQUESTED ?? null],
    ['待服务', counts?.WAIT_SERVICE ?? null],
    ['服务中', counts?.SERVING ?? null],
    ['已完成', counts?.COMPLETED ?? null],
  ]
  return (
    <Card title={`${date} 遛狗统计`}>
      {loading && !counts ? (
        <p className="py-6 text-center text-xs text-muted">
          <Spinner /> 加载中…
        </p>
      ) : (
        <>
          <ul className="flex flex-col gap-2">
            {boxes.map(([label, value]) => (
              <li key={label} className="flex items-center justify-between gap-2 text-[13px]">
                <span className="text-muted">{label}</span>
                <span className="font-black text-ink">{value ?? '—'}</span>
              </li>
            ))}
          </ul>
          {truncated && (
            <p className="mt-3 rounded-lg bg-blue-mist/60 px-3 py-2 text-[11px] leading-relaxed text-[#8A6116]">
              当天门店预约超过 200 条上限，计数可能不完整
            </p>
          )}
          <p className="mt-2 text-[11px] leading-relaxed text-muted">
            按上方所选日期统计，在该日全量遛狗队列上现算——不是「当前已显示条数」，也不是固定「今日」口径。
          </p>
        </>
      )}
    </Card>
  )
}

function StaffCard({ rows, queueDate, state, storeWide, onRetry }: { rows: ReturnType<typeof buildStaffLoad> | null; /** 负载所属的业务日（上方所选日期）；排班/在班固定为今日 */ queueDate: string; state: Block<unknown>['state']; storeWide: boolean | null; onRetry: () => void }) {
  const summary = rows ? staffingSummary(rows) : null
  return (
    <Card title="遛狗师在班与负载">
      {state === 'loading' && (
        <p className="py-6 text-center text-xs text-muted">
          <Spinner /> 加载中…
        </p>
      )}
      {state === 'error' && (
        <div className="flex flex-col items-center gap-2 py-5">
          <p className="text-xs text-[#E8506B]">名册加载失败</p>
          <button type="button" onClick={onRetry} className="h-7 rounded-lg border border-line px-3 text-[11px] font-semibold text-ink hover:border-blue hover:text-blue">
            重试
          </button>
        </div>
      )}
      {state === 'denied' && (
        <p className="py-5 text-[11px] leading-relaxed text-muted">
          本卡需要 <code className="font-mono">emp:view</code>（店长、前台有；遛狗师没有）→ 对这些角色不显示全店负载，属权限口径。
          你自己的任务在左侧队列里。
        </p>
      )}
      {state === 'ready' && rows && (
        <>
          {rows.length === 0 ? (
            <p className="py-5 text-center text-xs text-muted">
              本店名册里没有未离职员工（<code className="font-mono">/store/employees</code> 返回空）
            </p>
          ) : (
            <>
              <div className="mb-3 grid grid-cols-3 gap-1.5">
                {([['今日排班', summary?.scheduled ?? 0], ['今日在班', summary?.onDuty ?? 0], ['今日请假', summary?.onLeave ?? 0]] as [string, number][]).map(
                  ([label, value]) => (
                    <div key={label} className="rounded-lg bg-blue-mist/60 px-2 py-1.5 text-center ring-1 ring-line">
                      <p className="text-[10px] text-muted">{label}</p>
                      <p className="text-sm font-black text-ink">{value}</p>
                    </div>
                  ),
                )}
              </div>
            <ul className="flex flex-col gap-2.5">
              {rows.map((s) => (
                <li key={s.employeeId} className="flex items-center gap-2.5 rounded-xl bg-blue-mist/60 px-3 py-2 ring-1 ring-line">
                  <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-white text-blue">
                    <IconUsers className="h-4 w-4" />
                  </span>
                  <span className="min-w-0 flex-1">
                    <p className="truncate text-[13px] font-bold text-ink">
                      {s.name}
                      {!s.onDuty && s.pending + s.serving > 0 && <span className="ml-1.5 text-[11px] font-semibold text-[#E8506B]">未排班却有待办</span>}
                    </p>
                    <p className="truncate text-[11px] text-muted">
                      {s.onDuty ? '今日在班' : s.onLeave ? '今日请假' : '今日未排班'} · {s.roleCodes.length ? s.roleCodes.map((c) => ROLE_LABEL[c] ?? c).join('/') : '未分配角色'}
                    </p>
                    {(s.serving > 0 || s.cancelReview > 0 || s.done > 0) && (
                      <p className="truncate text-[10px] text-muted">
                        {[
                          s.serving > 0 ? `服务中 ${s.serving}` : null,
                          s.cancelReview > 0 ? `取消审核 ${s.cancelReview}` : null,
                          s.done > 0 ? `已完成 ${s.done}` : null,
                        ]
                          .filter(Boolean)
                          .join(' · ')}
                      </p>
                    )}
                  </span>
                  <span className="shrink-0 text-right text-[11px] text-muted">
                    <span className="block font-black text-ink">{s.pending + s.serving}</span>
                    待办
                  </span>
                </li>
              ))}
            </ul>
            </>
          )}
          <p className="mt-3 text-[11px] leading-relaxed text-muted">
            排班/在班＝<b className="text-ink">今日</b> <code className="font-mono">sch_schedule</code>（一人一日一班；
            名册端点只回今日班次，故左上三个数固定指今天，与上方日期无关）。负载＝
            <b className="text-ink">{queueDate}</b> 的遛狗队列按执行人现算：待办＝待签到＋待服务，
            服务中/取消审核中/已完成分列（取消审核中属"等门店审批"，不计入服务负载）。
            名册含全店员工（不按技能假装过滤）：没被分到遛狗单的人负载就是 0。
            {storeWide === false && ' 当前账号只看得到本人任务，故这里的计数只反映本人视野。'}
          </p>
        </>
      )}
    </Card>
  )
}

function BoundaryCard() {
  return (
    <Card title="本页能力边界">
      <ul className="flex flex-col gap-2.5 text-[13px]">
        {[
          ['真实数据已接入（ADR-049）', '全店/本人双视图、五态队列、日期筛选、履约记录、照片与 GPS 轨迹只读回显、宠物头像签名地址'],
          ['写侧已接', '到店签到、指派/改派、开始、完成＋记录落库、照片追加、GPS 轨迹保存、取消申请审核；闸：签到 appointment:checkin、指派 appointment:assign/reassign、开始/完成 task:start/task:finish、照片/轨迹 task:finish、审批 order:refund:approve——无权限仍 10003'],
          ['GPS 轨迹 / 里程（V16）', '服务中由员工主动录制；距离/时长/点数由服务端按有效点派生，C 端「服务报告」展示，并与服务实际时长分列'],
          ['遛狗照片', '完成后最多追加 9 张（WALK_PHOTO）；C 端只展示现签 GET 地址，签不出时明示不可读，不补图'],
          ['取消申请审核', '本页可按退款单同意/拒绝（复用订单退款端点）；同意后退款并取消，拒绝恢复 CONFIRMED'],
          ['代客下单遛狗', '已接在「客户管理 → 客户 360 → 新增预约」（不在本页重复入口）；可订服务走只读 bookable 端点（appointment:create），source=RECEPTIONIST'],
          ['台位 / 资源占用', '遛狗不占台位：上架写侧强制 required_resource_type=NULL；可约/创建/改期遇到空类型不查不锁资源'],
          ['宠物健康明细', '展开任务详情可「查看健康档案」：按需读单只宠物、不逐行 N+1；只读，编辑仍在「宠物管理」；无 pet:health:view（如遛狗师）不显示入口'],
        ].map(([label, why]) => (
          <li key={label} className="flex items-start gap-2">
            <span className="mt-1 h-1.5 w-1.5 shrink-0 rotate-45 bg-[#2FA96B]" />
            <span className="min-w-0 flex-1">
              <span className="font-semibold text-ink">{label}</span>
              <span className="block text-[11px] text-muted">{why}</span>
            </span>
          </li>
        ))}
        {[
          ['排便次数', 'ful_walk_record.poop_count 有列，但全链路仍无写入入口与产品口径；不展示假次数'],
          ['真实退款渠道', '当前 MockRefundExecutor 只报成功，未调用支付渠道、未写 pay_refund_txn；接真实渠道前退款为模拟执行'],
          ['地图 / 导航', '只有内联 SVG 示意路线，未接地图 SDK，不提供真实地图或导航'],
          ['照片 / 轨迹不可修改', '照片与轨迹均为追加后只读（无删除/替换/改点入口）；照片上限 9 张、轨迹点上限 2000'],
          ['GPS 仅前台主动录制', '需员工手动开始且页面在前台；离开页面/切店即停，不做后台持续定位'],
        ].map(([label, why]) => (
          <li key={label} className="flex items-start gap-2">
            <span className="mt-1 h-1.5 w-1.5 shrink-0 rotate-45 bg-[#E8506B]" />
            <span className="min-w-0 flex-1">
              <span className="font-semibold text-ink">{label}</span>
              <span className="block text-[11px] text-muted">{why}</span>
            </span>
          </li>
        ))}
      </ul>
    </Card>
  )
}

/* ---- 通用小件（与洗澡页同款） ---- */

function Badge({ label, tone }: { label: string; tone: Tone }) {
  return (
    <span className="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold" style={{ background: TONE[tone].bg, color: TONE[tone].fg }}>
      {label}
    </span>
  )
}

function Field({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div>
      <p className="text-[11px] text-muted">{label}</p>
      <p className={cx('mt-0.5 text-sm font-semibold text-ink', mono && 'font-mono text-[13px]')}>{value}</p>
    </div>
  )
}

function ActionButton({ Icon, label, onClick, disabled, busy }: { Icon: typeof IconUser; label: string; onClick: () => void; disabled?: boolean; busy?: boolean }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={cx(
        'flex h-9 items-center gap-1.5 rounded-lg bg-white px-3.5 text-xs font-semibold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue',
        disabled && 'cursor-not-allowed opacity-50 hover:text-ink hover:ring-line',
      )}
    >
      {busy ? <Spinner /> : <Icon className="h-4 w-4" />} {label}
    </button>
  )
}

function Spinner() {
  return <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-blue border-t-transparent align-[-2px]" aria-hidden="true" />
}

function ListLoading() {
  return (
    <div className="flex flex-col gap-3" role="status" aria-label="队列加载中">
      {[0, 1, 2].map((i) => (
        <div key={i} className="flex h-[104px] items-center justify-center rounded-2xl bg-blue-mist/60 ring-1 ring-line">
          {i === 0 && (
            <span className="text-xs text-muted">
              <Spinner /> 加载遛狗队列…
            </span>
          )}
        </div>
      ))}
    </div>
  )
}

function ListError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-2xl bg-blue-mist/60 py-12 ring-1 ring-line">
      <p className="text-sm text-[#E8506B]">队列加载失败：{message}</p>
      <button type="button" onClick={onRetry} className="h-9 rounded-lg border border-line bg-white px-5 text-xs font-semibold text-ink transition hover:border-blue hover:text-blue">
        重试
      </button>
    </div>
  )
}
