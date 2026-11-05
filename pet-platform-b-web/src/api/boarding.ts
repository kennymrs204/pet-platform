import { http } from './client'
import type { AppointmentItem, BoardingCustomer, BoardingStayItem, PageData, PetRef } from './dashboard'

/**
 * B 端托管服务只读接口（后端契约：ADR-015 / ADR-016）。
 * 关键约束（以代码为准）：
 * - `GET /store/boarding-stays` **无分页**，上限 50 条（LIST_LIMIT），排序 checkin_date→id；
 *   `status` 支持逗号多值，**缺省只返回在店集合 {CHECKED_IN,DAILY_CARE}** → 需要全量时须显式传五种状态；
 * - 数据范围：持 appointment:view:store（店长/前台）见全店，任务角色仅见 caretaker=本人；
 * - 权限码 boarding:care:view（列表/详情/护理记录）与 boarding:capacity:view（笼位容量）不同，
 *   故笼位卡可能单独 10003，必须独立成块降级；跨店住单详情统一 10004（防枚举）。
 * - Long **及 long 基本类型**一律字符串化（int 不会）：id/petId/caretakerId/kennelResourceId
 *   以及笼位 total/occupied/available 全是字符串，参与运算前须显式转换。
 */

export type StayStatus = 'EXPECTED' | 'CHECKED_IN' | 'DAILY_CARE' | 'CHECKED_OUT' | 'ABNORMAL'
/** 会随时间累积、因此需要分页的历史态（后端 HISTORY_STATUSES 的同一份口径） */
export type HistoryStayStatus = Extract<StayStatus, 'CHECKED_OUT' | 'ABNORMAL'>
export const HISTORY_PAGE_SIZE = 20

export interface StayHistoryQuery {
  status: HistoryStayStatus
  pageNum: number
  pageSize?: number
  /** ISO 日期（yyyy-MM-dd），打在离店日上，闭区间 */
  dateFrom?: string
  dateTo?: string
}
export type KennelType = 'KENNEL_SMALL' | 'KENNEL_MEDIUM' | 'KENNEL_LARGE' | 'CAT_ROOM'

/** 后端 ALL_STATUSES：列表可请求的全部状态（页签计数需全量，故一次拉齐） */
export const ALL_STAY_STATUSES: StayStatus[] = ['EXPECTED', 'CHECKED_IN', 'DAILY_CARE', 'CHECKED_OUT', 'ABNORMAL']

/** 列表条目复用首页已核实的同一份响应类型（同一端点，不重复定义） */
export type StayItem = BoardingStayItem

export interface StayDetail {
  id: string
  appointmentId: string
  petId: string
  storeId: string
  status: StayStatus
  checkinDate: string
  checkoutDate: string
  actualCheckinAt: string | null
  actualCheckoutAt: string | null
  kennelResourceId: string | null
  caretakerId: string | null
  specialInstructions: string | null
}

export interface CareRecord {
  id: string
  careDate: string
  caretakerId: string
  /** 后端 B 端列表批量回填；查无/已删为 null */
  caretakerName?: string | null
  feeding: { count: number | null; amountText: string | null } | null
  watering: { count: number | null } | null
  walk: { count: number | null; durationMin: number | null } | null
  observation: string | null
  photos: string[] | null
  /** presigned 读取 URL（ADR-029，与 photos 同序）；后端未开启对象存储时为空数组 */
  photoUrls: string[] | null
  abnormalFlag: boolean
  abnormalDesc: string | null
}

/**
 * 全店「今日护理动态」行（GET /store/boarding/care-activity，ADR-032）。
 * 刻意**不含 photos/photoUrls**：动态卡只列文本，逐条 presign 会把一次查询放大成 M 次签名；
 * 要看当日照片走单住单的 careRecords(id)。id 字段名是 recordId（与 CareRecord.id 不同源，别混用）。
 */
export interface CareActivityEntry {
  recordId: string
  stayId: string
  petId: string
  /** 宠物已删/不可见时后端给 null → 前端回落占位，不编名字 */
  petName: string | null
  careDate: string
  caretakerId: string
  caretakerName: string | null
  feeding: CareRecord['feeding']
  watering: CareRecord['watering']
  walk: CareRecord['walk']
  observation: string | null
  abnormalFlag: boolean
  abnormalDesc: string | null
}

export interface KennelCapacity {
  type: KennelType
  /** 后端 KennelCapacityView 用 long 基本类型，仍被 Jackson 字符串化（int 不会）→ 一律按 string 接收 */
  total: string
  occupied: string
  available: string
}

export interface CheckinBody {
  /** 留空=沿用预约预占笼位；无预占且仍留空 → 后端 40005。候选来自 GET /store/boarding/kennels?stayId= */
  kennelResourceId?: string | null
  /** 主责护理员；候选来自 GET /store/boarding/assignable-staff（该端点已收敛为在职且可指派员工） */
  caretakerId?: string | null
  specialInstructions?: string | null
}

export interface CareRecordBody {
  /** 留空=后端业务今日（Asia/Shanghai）；不得晚于今日、须在入住区间内 */
  careDate?: string
  feeding?: { count?: number; amountText?: string }
  watering?: { count?: number }
  walk?: { count?: number; durationMin?: number }
  observation?: string
  photos?: string[]
  abnormalFlag?: boolean
  abnormalDesc?: string
}

/** 入住选笼位的候选（GET /store/boarding/kennels?stayId=，perm boarding:capacity:view） */
export interface KennelOption {
  id: string
  type: KennelType
  name: string
  spanFrom: string
  spanToExclusive: string
  /** false=该住单计划区间内已有占用；选项里保留可见但禁用（真正冲突由 checkin 抛 72001） */
  freeForSpan: boolean
}

/** 本店可指派在职员工（GET /store/boarding/assignable-staff，perm boarding:checkin） */
export interface StoreStaff {
  employeeId: string
  name: string | null
  roleCodes: string[]
  rolesLabel: string | null
}

/**
 * 待支付托管预约（GET /store/boarding/pending-appointments，ADR-030，perm boarding:care:view）。
 * 这类预约**不会**出现在住单列表里（住单由支付成功事件链预生成，ADR-016），本视图是它在 B 端唯一的出口：
 * 只读、无任何写动作入口；收款仍由顾客在 C 端完成，超时未付会被自动取消并释放笼位预占。
 * `nights` 是 int（Jackson 只字符串化 Long/long）→ 直接用数字；金额 `priceSnapshot` 仍是字符串分。
 */
export interface PendingBoardingAppointment {
  id: string
  appointmentNo: string
  /** 恒 PENDING_PAYMENT（后端回显，供审计不变量） */
  status: string
  checkinDate: string
  checkoutDate: string
  nights: number
  serviceName: string | null
  priceSnapshot: string
  expireAt: string | null
  createdAt: string | null
  pet: PetRef | null
  /** 客户（宠物主登记人）；无 customer:view:store 时为 null（同住单列表口径） */
  customer: BoardingCustomer | null
}

/**
 * 主列表分页查询（V3）。`statuses` 由页签语义决定（见 lib/boardingView 的 BOARDING_TAB_STATUSES），
 * `keyword` 交给服务端匹配宠物名/客户昵称/手机号/住单 id/预约 id —— 覆盖本店全部符合条件的住单，
 * 不再是"已加载的那 50 条里筛"。
 */
export interface StayPageQuery {
  statuses: StayStatus[]
  keyword?: string
  pageNum: number
  pageSize: number
}

/**
 * 页签角标（服务端一次 GROUP BY）。long 被 Jackson 字符串化 → 前端取值要 Number()。
 * 「在店中」的合并规则（CHECKED_IN + DAILY_CARE）留在服务端，前端不再重述一遍状态语义。
 */
export interface StayStatusCounts {
  all: string
  inStore: string
  expected: string
  checkedOut: string
  abnormal: string
}

export const boardingApi = {  stays: (statuses: StayStatus[] = ALL_STAY_STATUSES) =>
    http.get<StayItem[]>(`/store/boarding-stays?status=${statuses.join(',')}`, { auth: true }),
  /**
   * 主列表服务端分页 + 服务端关键词搜索（V3）。
   * 既有 `stays()`（裸数组、上限 50）刻意保留：首页在店卡与宠物照顾工作台仍在用；
   * 同一 URL 出现两种响应外壳是最容易出错的一类契约，所以分页形态另开 /page 出口。
   */
  stayPage: (q: StayPageQuery) => {
    const p = new URLSearchParams({
      status: q.statuses.join(','),
      pageNum: String(q.pageNum),
      pageSize: String(q.pageSize),
    })
    const kw = q.keyword?.trim()
    if (kw) p.set('keyword', kw)
    return http.get<PageData<StayItem>>(`/store/boarding-stays/page?${p.toString()}`, { auth: true })
  },
  /** 页签角标：与列表同源同条件（含关键词），因此切页签时角标语义保持不变。 */
  statusCounts: (keyword?: string) => {
    const kw = keyword?.trim()
    return http.get<StayStatusCounts>(
      `/store/boarding-stays/status-counts${kw ? `?keyword=${encodeURIComponent(kw)}` : ''}`,
      { auth: true },
    )
  },
  /** petId 可选：带上则服务端校验住单归属该宠物，不匹配按不存在（10004），防跨住单 ID 窥探 */
  detail: (id: string, petId?: string) =>
    http.get<StayDetail>(`/store/boarding-stays/${id}${petId ? `?petId=${encodeURIComponent(petId)}` : ''}`, { auth: true }),
  careRecords: (id: string, petId?: string) =>
    http.get<CareRecord[]>(
      `/store/boarding-stays/${id}/care-records${petId ? `?petId=${encodeURIComponent(petId)}` : ''}`,
      { auth: true },
    ),
  capacity: () => http.get<KennelCapacity[]>('/store/boarding/capacity', { auth: true }),

  /**
   * 4.4 新增托管 = 从「本店已支付的跨天托管预约」补建待入住单（POST 无请求体，
   * 宠物/日期/门店全部由后端取预约快照，前端没有可注入面）。
   * 候选直接复用首页已核实的预约看板（status=CONFIRMED），非托管预约由后端 10001/50003 权威拒绝。
   */
  provisionCandidates: () =>
    http.get<PageData<AppointmentItem>>('/store/appointments?status=CONFIRMED&pageNum=1&pageSize=50', { auth: true }),
  provision: (appointmentId: string) =>
    http.post<StayDetail>(`/store/boarding-stays/from-appointment/${appointmentId}`, undefined, { auth: true }),

  /** 4.5 写侧三动作（服务端权威校验，前端只做防呆；状态机 EXPECTED→CHECKED_IN→DAILY_CARE→CHECKED_OUT） */
  checkin: (stayId: string, body: CheckinBody) =>
    http.post<StayDetail>(`/store/boarding-stays/${stayId}/checkin`, body, { auth: true }),
  /**
   * 在住单补派/改派主责护理员（服务端条件更新：WHERE id+store_id+status IN (CHECKED_IN,DAILY_CARE)）。
   * 权限复用 boarding:checkin（与入住时指派同一码）；非在住 50003、非本店有效员工 10001、跨店 10004。
   * 只改 caretaker_id：不动状态、笼位、日期，也不开放店长代写护理记录（写闸仍只认本字段）。
   */
  assignCaretaker: (stayId: string, caretakerId: string) =>
    http.post<StayDetail>(`/store/boarding-stays/${stayId}/caretaker`, { caretakerId }, { auth: true }),

  checkout: (stayId: string) =>
    http.post<StayDetail>(`/store/boarding-stays/${stayId}/checkout`, undefined, { auth: true }),
  /** 仅该单主责护理员可写（店长代班未开放，ADR-015 §5）；首条记录会把 CHECKED_IN 推进为 DAILY_CARE */
  addCareRecord: (stayId: string, body: CareRecordBody) =>
    http.post<CareRecord>(`/store/boarding-stays/${stayId}/care-records`, body, { auth: true }),

  /** 4.6 入住表单选择器：笼位按住单计划区间标注可用性；员工名单为本店在职可指派者 */
  kennelOptions: (stayId: string) => http.get<KennelOption[]>(`/store/boarding/kennels?stayId=${stayId}`, { auth: true }),
  assignableStaff: () => http.get<StoreStaff[]>('/store/boarding/assignable-staff', { auth: true }),

  /**
   * 5.6 历史住单分页（ADR-032 片2，perm boarding:care:view）。
   * **只接受历史态**（`CHECKED_OUT`/`ABNORMAL`）——在住/待入住走 `stays()`，两个端点语义不重叠；
   * 状态集里没有 CANCELLED（取消=逻辑删，ADR-023）。
   * 排序按 `checkout_date DESC`，`dateFrom/dateTo` 打在 `checkout_date` 上（与排序同列）。
   * 不提供关键词：跨模块"名字→id 集合"会成为第二套真相，翻历史用日期区间（ADR-032 §3 拍板）。
   */
  history: (q: StayHistoryQuery) => {
    const p = new URLSearchParams({ status: q.status, pageNum: String(q.pageNum), pageSize: String(q.pageSize ?? HISTORY_PAGE_SIZE) })
    if (q.dateFrom) p.set('dateFrom', q.dateFrom)
    if (q.dateTo) p.set('dateTo', q.dateTo)
    return http.get<PageData<StayItem>>(`/store/boarding-stays/history?${p.toString()}`, { auth: true })
  },

  /** 5.4 待支付托管预约看板（ADR-030）：days=从今日起的入住日预览窗（1..60，越界 10001） */
  pendingAppointments: (days = 14) =>
    http.get<PendingBoardingAppointment[]>(`/store/boarding/pending-appointments?days=${days}`, { auth: true }),

  /**
   * 5.5 全店「今日护理动态」（ADR-032，perm boarding:care:view）：一次取回当日全店记录，
   * 取代旧的「对在住前 5 单扇面拉 careRecords 再本地按日期过滤」（N+1，且第 6 单之后的记录永远看不到）。
   * date 缺省=业务今日（后端 Asia/Shanghai）；晚于今日 → 10001 显式拒绝。
   * 范围与住单列表同源：店范围码看全店，护理员只看自己被指派的单（后端 SQL 内收窄）。
   */
  careActivity: (date?: string) =>
    http.get<CareActivityEntry[]>(`/store/boarding/care-activity${date ? `?date=${date}` : ''}`, { auth: true }),
}

export const STAY_STATUS_LABEL: Record<StayStatus, string> = {
  EXPECTED: '待入住',
  CHECKED_IN: '入住中',
  DAILY_CARE: '每日护理中',
  CHECKED_OUT: '已离店',
  ABNORMAL: '异常',
}

/** 在店集合（与后端 IN_STORE_STATUSES 一致，缺省过滤即此二者） */
export const IN_STORE_STATUSES: StayStatus[] = ['CHECKED_IN', 'DAILY_CARE']

export function isInStore(status: string): boolean {
  return (IN_STORE_STATUSES as string[]).includes(status)
}

export const KENNEL_TYPE_LABEL: Record<KennelType, string> = {
  KENNEL_SMALL: '小型犬笼',
  KENNEL_MEDIUM: '中型犬笼',
  KENNEL_LARGE: '大型犬笼',
  CAT_ROOM: '猫舍',
}

/** 计划入住/离店日期差 → 「N 天」；同日返回「1 天」；非法日期返回缺省（不猜） */
export function stayNightsText(checkin: string | null, checkout: string | null): string {
  if (!checkin || !checkout) return '日期未定'
  const a = Date.parse(`${checkin}T00:00:00`)
  const b = Date.parse(`${checkout}T00:00:00`)
  if (Number.isNaN(a) || Number.isNaN(b)) return '日期未定'
  const days = Math.round((b - a) / 86_400_000)
  return days <= 0 ? '1 天' : `${days} 天`
}

/** ISO 时间戳 → 「09-25 14:03」（不显示年份，年份在详情里给全量） */
export function shortDateTime(iso: string | null): string {
  if (!iso) return '--'
  const m = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/.exec(iso)
  return m ? `${m[2]}-${m[3]} ${m[4]}:${m[5]}` : iso
}

/**
 * 护理记录 → 一行摘要文案（全字段缺失时返回 null，由调用方走空态，不编造）。
 * 入参取结构子集：CareRecord（单住单）与 CareActivityEntry（全店聚合）共用同一份摘要口径，
 * 不为了迁就聚合视图而复制第二个 careSummary。
 */
export function careSummary(r: Pick<CareRecord, 'feeding' | 'watering' | 'walk' | 'observation'>): string | null {
  const parts: string[] = []
  if (r.feeding?.count != null) parts.push(`喂食 ${r.feeding.count} 次${r.feeding.amountText ? `（${r.feeding.amountText}）` : ''}`)
  if (r.watering?.count != null) parts.push(`饮水 ${r.watering.count} 次`)
  if (r.walk?.count != null) parts.push(`遛弯 ${r.walk.count} 次${r.walk.durationMin != null ? ` ${r.walk.durationMin} 分钟` : ''}`)
  if (r.observation) parts.push(r.observation)
  return parts.length ? parts.join(' · ') : null
}
