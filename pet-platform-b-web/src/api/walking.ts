import { http } from './client'

/**
 * B 端「遛狗服务」= 遛狗履约看板的接口层（ADR-049）。
 *
 * **直连真实端点，不做演示回退**：契约 = `GET /store/walking/queue`。此前全店遛狗队列无端点——
 * 洗护看板（`GET /store/grooming/queue`）刻意排除遛狗单（类型判据 `cat_service.code` ∈ {PET_BATH, PET_GROOMING}），
 * 「我的任务」（`GET /store/tasks/mine`）只有服务端收窄后的本人视角，遛狗单只出现在后者里。
 *
 * 写侧**不在此定义**：到店签到 / 指派 / 开始 / 完成四个动作与洗护共用同一批端点
 * （`/store/appointments/{id}/…`，ADR-042/048——动作挂在预约上，与服务类型无关），
 * 页面直接复用 `bathingApi` 的同名方法，不复制第二份会漂移的真相。
 * 完成遛狗时后端同事务落 `ful_walk_record`：该表没有执行项目与照片列，distance_m/track_summary/poop_count
 * 全链路无写入入口（ADR-048 刻意留 NULL）→ 出参与表单都不含这些字段，不编 UI。
 */

export type WalkQueueStatus = 'CONFIRMED' | 'CANCEL_REQUESTED' | 'WAIT_SERVICE' | 'SERVING' | 'COMPLETED'

export interface WalkPetRef {
  id: string
  name: string
  species: string
  breed: string | null
  gender: string | null
  sizeCategory: string | null
  /** presigned GET（ADR-029，600s）；签不出为 null → 前端回落爪印占位，不放假照片 */
  avatarUrl: string | null
}

export interface WalkEmployeeRef {
  id: string
  name: string
}

/** 遛狗履约记录摘要，字段严格照 ful_walk_record（V1:453-475）：没有执行项目列，里程轨迹不采集。 */
export interface WalkRecordRef {
  employeeId: string | null
  startedAt: string | null
  finishedAt: string | null
  /** 实际遛狗时长（分钟，完成时由实起止差实算） */
  durationMin: number | null
  remark: string | null
  /** 店长/前台代点（点的人 ≠ 执行人） */
  byManager: boolean | null
  /** 照片对象 key（u-{uid}/WALK_PHOTO/…）；无照片=空数组，绝不会是 null */
  photoKeys: string[] | null
  /** 与 photoKeys 同序的 presigned GET（600s）；对象存储未启用时为空数组 */
  photoUrls: string[] | null
}

export interface WalkingTask {
  appointmentId: string
  appointmentNo: string
  serviceDate: string
  /** 「HH:mm」（JacksonConfig 里 LocalTime 输出 HH:mm），未定档为 null */
  startTime: string | null
  endTime: string | null
  status: WalkQueueStatus
  /** CUSTOMER=顾客自约 / RECEPTIONIST=前台代录 */
  source: string | null
  serviceName: string | null
  /** 分（后端 Long → 全局 Jackson 字符串化） */
  price: string | null
  storeServiceId: string | null
  serviceId: string | null
  serviceCode: string | null
  /** 恒 'WALK'（DOG_WALK 之外的不进这条队列）；保留字段为与洗护看板同构 */
  serviceType: 'WALK' | null
  durationMin: number | null
  pet: WalkPetRef | null
  /** null = 尚未指派执行人 */
  employee: WalkEmployeeRef | null
  assignedToMe: boolean | null
  /** null = 尚未提交履约记录（页面据此区分「未开始」与「已完成」，不猜） */
  record: WalkRecordRef | null
  /** null = 未采集 GPS 轨迹（轨迹可在服务中保存，故与 record 平级；无轨迹≠0 公里） */
  track: WalkTrackSummary | null
}

/** 照片追加的响应（key 与 URL 同序；无照片时两者都是空数组）。 */
export interface WalkPhotosView {
  appointmentId: string
  photos: string[]
  photoUrls: string[]
}

/** 轨迹点：WGS84 坐标 + 采集时间（epoch millis）+ 定位精度（米，0=未知）。 */
export interface WalkTrackPoint {
  lat: number
  lng: number
  atMillis: number
  /** 浏览器 Geolocation 的 coords.accuracy（水平精度半径，米）；未知时传 0 */
  accuracyM: number
}

/**
 * 轨迹摘要（服务端持久化值）。距离/时长/点数都是**保存时由服务端派生**的，
 * 前端只回显、不重算——避免两套算法给出两个里程。
 */
export interface WalkTrackSummary {
  id: string
  appointmentId: string
  trackId: string
  /** 首个有效点的采集时间 */
  startedAt: string
  /** 最后一个有效点的采集时间 */
  endedAt: string
  /** 实际记录时长（秒）= 末点 − 首点 − 暂停 */
  durationSec: number
  pausedSec: number
  distanceM: number
  /** 有效点数（服务端清洗后） */
  pointCount: number
  /** 抽稀后的 [lat,lng] 序列，仅用于画示意路线 */
  polyline: [number, number][]
}

/** 队列信封。`storeWideScope` 由服务端告知（只有 `task:accept` 的人被强制收窄成本人范围），前端据此如实标注视图而不猜权限。 */
export interface WalkQueueView {
  date: string
  storeWideScope: boolean
  /** 调用者是否持调度权限（appointment:assign/reassign，ADR-054）——指派控件按此分流，员工不猜角色 */
  canAssign: boolean
  /** 当天门店预约超过后端 200 条上限 → 队列可能不完整；计数为真但样本不全时页面要说话 */
  truncated: boolean
  total: string
  list: WalkingTask[]
}

export const walkingApi = {
  /** `GET /store/walking/queue?date=`（ADR-049）。闸 = `appointment:view:store`（全店）或 `task:accept`（强制仅本人）。 */
  queue: (date?: string) =>
    http.get<WalkQueueView>(`/store/walking/queue${date ? `?date=${date}` : ''}`, { auth: true }),

  /**
   * 给<b>已完成</b>的遛狗记录追加照片（2026-10-24，闸 `task:finish`）。
   * 只提交对象 key——二进制走 `/uploads/intent` 直传，不进业务表。
   * 服务端复核：门店/人员/服务类型/完成态/key 归属/对象存在性/总数上限；
   * 返回追加后的完整 key 列表与现签 URL（同序）。
   */
  addPhotos: (appointmentId: string, mediaKeys: string[]) =>
    http.post<WalkPhotosView>(`/store/walking/${appointmentId}/photos`, { mediaKeys }, { auth: true }),

  /**
   * 保存一次遛狗 GPS 轨迹（闸 `task:finish`，仅服务中/已完成可用）。
   * **只提交原始坐标**：距离/实际记录时长/有效点数由服务端清洗后派生并落库，前端不传任何算好的里程。
   * 幂等键 `trackId` 必须与本次轨迹一起固定：重试沿用同一个值 ⇒ 服务端返回已存轨迹；换值 ⇒ 10005。
   */
  saveTrack: (
    appointmentId: string,
    body: { trackId: string; pausedSec: number; points: WalkTrackPoint[] },
  ) => http.post<WalkTrackSummary>(`/store/walking/${appointmentId}/track`, body, { auth: true }),
}

export const WALK_QUEUE_STATUS_LABEL: Record<WalkQueueStatus, string> = {
  CONFIRMED: '待签到',
  /** ADR-043：客户已提交取消申请，等门店审批；遛狗时段仍占着 */
  CANCEL_REQUESTED: '取消审核中',
  WAIT_SERVICE: '待服务',
  SERVING: '服务中',
  COMPLETED: '已完成',
}
export const WALK_QUEUE_STATUSES: WalkQueueStatus[] = ['CONFIRMED', 'CANCEL_REQUESTED', 'WAIT_SERVICE', 'SERVING', 'COMPLETED']

/**
 * 客户端过滤（端点一次给完当天全量遛狗单，页签计数是真数，不是"已取回条数冒充全店数"）。
 * mineOnly 直接用服务端的 `assignedToMe`，前端不猜权限、也不自带身份参数。
 */
export function filterWalkTasks(list: WalkingTask[], f: { status?: WalkQueueStatus; mineOnly?: boolean }): WalkingTask[] {
  return list
    .filter((t) => !f.status || t.status === f.status)
    .filter((t) => !f.mineOnly || t.assignedToMe === true)
    .sort((a, b) => (a.startTime ?? '99').localeCompare(b.startTime ?? '99'))
}

export function walkTabCounts(list: WalkingTask[]): Record<'ALL' | WalkQueueStatus, number> {
  const n = (s: WalkQueueStatus) => list.filter((t) => t.status === s).length
  return {
    ALL: list.length,
    CONFIRMED: n('CONFIRMED'),
    CANCEL_REQUESTED: n('CANCEL_REQUESTED'),
    WAIT_SERVICE: n('WAIT_SERVICE'),
    SERVING: n('SERVING'),
    COMPLETED: n('COMPLETED'),
  }
}

/** 队列行的时段文案：与洗护看板同口径（bathing.taskTimeText 收 BathingTask，不跨类型复用）。 */
export function walkTimeText(t: WalkingTask): string {
  if (!t.startTime) return '时间待定'
  const s = t.startTime.slice(0, 5)
  return t.endTime ? `${s}–${t.endTime.slice(0, 5)}` : `${s} 起`
}
