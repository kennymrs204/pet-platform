import { http } from './client'
import { SKILL_LABEL, type EmployeeRow } from './employees'

/**
 * B 端「洗澡服务」= 洗护（洗澡 / 美容）履约看板的接口层。
 *
 * **直连真实端点，不做演示回退**（ADR-038）：契约 = 后端 `GET /store/grooming/queue` 与
 * `GET /store/grooming/stations`。这一页曾是 B 端唯一整块 mock 的页面，理由成立——队列需要
 * 「服务类型 / 台位 / 履约记录」三类字段，而 `GET /store/appointments` 一个都不给（只有会随服务
 * 改名漂移的 `serviceName` 快照），且 tasks 链（P8 :230-231）后端零实现，直连只会拿到一张空表。
 * ADR-038 补齐了读侧三类字段：服务类型的唯一可靠判据是 `cat_service.code`（PET_BATH/PET_GROOMING，
 * 两者同落 `ful_bath_record`，所以是一条队列两类服务，不拆两页）。
 *
 * 写侧现已落地四条：到店签到（ADR-042）、分配/改派、开始服务、完成服务＋履约记录（ADR-048）。
 * 仍未接入的是「换台位」与「门店侧取消预约」：前者要动 `sch_resource_schedule` 的占用迁移
 * （库里只有 confirmOrMove/occupy/release 原语，没有"给已确认单换台位"的业务裁决），后者牵退款政策链
 * （ADR-023 只在 C 端落地）——两者都不该被一个 toast 假装做成。
 */

export type BathQueueStatus = 'CONFIRMED' | 'CANCEL_REQUESTED' | 'WAIT_SERVICE' | 'SERVING' | 'COMPLETED'
export type BathServiceType = 'BATH' | 'GROOMING'

export interface BathPetRef {
  id: string
  name: string
  species: string
  breed: string | null
  gender: string | null
  sizeCategory: string | null
  /** presigned GET（ADR-029，600s）；签不出为 null → 前端回落爪印占位，不放假照片 */
  avatarUrl: string | null
}

export interface BathEmployeeRef {
  id: string
  name: string
}

/** 履约记录摘要，字段严格照 ful_bath_record（V1:431-451）：库里没有水温/用水/沐浴用品/异常标记列。 */
export interface BathRecord {
  employeeId: string | null
  startedAt: string | null
  finishedAt: string | null
  /** 实际执行项目（emp_skill 字典 code） */
  serviceItems: string[] | null
  remark: string | null
  /** 照片张数。出参刻意不含 key/URL（同 ADR-032 对护理动态的取舍：逐条 presign 会把一次查询放大成 M 次签名） */
  photoCount: number | null
}

export interface BathingTask {
  appointmentId: string
  appointmentNo: string
  serviceDate: string
  /** 「HH:mm」（JacksonConfig 里 LocalTime 输出 HH:mm），未定档为 null */
  startTime: string | null
  endTime: string | null
  status: BathQueueStatus
  /** CUSTOMER=顾客自约 / RECEPTIONIST=前台代录 */
  source: string | null
  serviceName: string | null
  /** 分（后端 Long → 全局 Jackson 字符串化） */
  price: string | null
  storeServiceId: string | null
  serviceId: string | null
  serviceCode: string | null
  serviceType: BathServiceType | null
  durationMin: number | null
  /** 该服务要求的资源类型（洗澡=BATH_STATION，美容常为 null=不占台位） */
  requiredResourceType: string | null
  pet: BathPetRef | null
  /** null = 尚未指派执行人 */
  employee: BathEmployeeRef | null
  assignedToMe: boolean | null
  /** null = 尚未提交履约记录（页面据此区分「未开始」与「已完成」，不猜） */
  record: BathRecord | null
}

export interface BathStation {
  resourceId: string
  name: string
  type: string
  status: string
  /** 已占用的 30 分钟槽号（0..47，slot 20 = 10:00–10:30，ADR-005） */
  occupiedSlots: number[]
}

export interface StaffLoad {
  employeeId: string
  name: string
  roleCodes: string[]
  /**
   * 今日是否排班在班（来源 `sch_schedule` 当日行，与 emp_employee.status 是两回事）。
   * 名册端点只有**今日**班次（`StoreEmployeeController` 固定 today..today），
   * 因此本字段与"负载"的日期口径可能不同——展示时必须标注"今日"（见 Walk StaffCard）。
   */
  onDuty: boolean
  /** 今日是否有排班行（含请假：`sch_schedule.status=LEAVE` 也是一次排班记录） */
  scheduled: boolean
  /** 今日班次状态为 LEAVE */
  onLeave: boolean
  /**
   * 任务负载：**按传入队列**（遛狗页=所选日期的 DOG_WALK 队列）现算。
   * 待开工=CONFIRMED+WAIT_SERVICE；服务中=SERVING；已完成=COMPLETED；
   * CANCEL_REQUESTED 单列 cancelReview（等门店审批，不属于服务负载）。
   */
  pending: number
  serving: number
  done: number
  cancelReview: number
}

/** 队列信封。`storeWideScope` 由服务端告知（只有 `task:accept` 的人被强制收窄成本人范围），前端据此如实标注视图而不猜权限。 */
export interface BathQueueView {
  date: string
  storeWideScope: boolean
  /** 调用者是否持调度权限（appointment:assign/reassign，ADR-054）——指派控件按此分流，员工不猜角色 */
  canAssign: boolean
  /** 当天预约超过后端 200 条上限 → 队列可能不完整；计数为真但样本不全时页面要说话 */
  truncated: boolean
  total: string
  list: BathingTask[]
}

export interface BathQueueParams {
  date?: string
  /** BATH | GROOMING | 不传=两类都要。服务端过滤，避免"拉全量再前端砍"在多日查询时口径漂移 */
  type?: BathServiceType
}

export const bathingApi = {
  /**
   * `GET /store/grooming/queue?date=&type=`（ADR-038）。
   * 闸 = `appointment:view:store`（店长/前台，全店）或 `task:accept`（美容师/医生/遛狗师，强制仅本人）。
   */
  queue: (p: BathQueueParams = {}) => {
    const q = new URLSearchParams()
    if (p.date) q.set('date', p.date)
    if (p.type) q.set('type', p.type)
    return http.get<BathQueueView>(`/store/grooming/queue?${q.toString()}`, { auth: true })
  },

  /** `GET /store/grooming/stations?date=`（ADR-038）：只列 AVAILABLE 台位 + 当日占用槽。 */
  stations: (date?: string) =>
    http.get<BathStation[]>(`/store/grooming/stations${date ? `?date=${date}` : ''}`, { auth: true }),

  /**
   * 到店签到 `POST /store/appointments/{id}/checkin`（ADR-042，闸 `appointment:checkin` = 店长+前台）。
   * 路径属预约域（表 Owner=appointment），本页只是它唯一的 B 端入口。
   * 幂等：已签到过再点回 `changed=false`（不是错误），调用方据此换文案。
   */
  checkin: (appointmentId: string) =>
    http.post<CheckinResult>(`/store/appointments/${appointmentId}/checkin`, undefined, { auth: true }),

  /**
   * 分配/改派服务人员 `PUT /store/appointments/{id}/assign-employee`（ADR-048）。
   * 闸 = `appointment:assign`（首派）；该单已有别的执行人时后端还要 `appointment:reassign`。
   * 后端会校验目标员工在本店在职、当日有班、技能覆盖该服务、且该时段不与他的其它预约冲突；
   * 已开始（SERVING）的单会被 50004 拒——所以按钮只在 CONFIRMED/WAIT_SERVICE 出现。
   */
  assignEmployee: (appointmentId: string, employeeId: string) =>
    http.put<TaskResult>(`/store/appointments/${appointmentId}/assign-employee`, { employeeId }, { auth: true }),

  /**
   * 换台位 `PUT /store/appointments/{id}/resource`（ADR-048 §8 核销）。
   * 权限口径同 assign（appointment:assign/reassign）；只允许未开始（CONFIRMED/WAIT_SERVICE），
   * 已开始 50004；目标台位该时段被其他预约占用回 40004「请重挑」——后端不自动换第二选择。
   * 预约表没有 resource_id 列，占用行是唯一真相（ADR-038）；目标==当前台位幂等 changed=false。
   */
  changeResource: (appointmentId: string, resourceId: string) =>
    http.put<TaskResult>(`/store/appointments/${appointmentId}/resource`, { resourceId }, { auth: true }),

  /**
   * 开始服务 `POST /store/appointments/{id}/start-service`（ADR-048，闸 `task:start`）。
   * CONFIRMED 或 WAIT_SERVICE 都能开始（有的门店不做签到动作）；写 `actual_start_at`。
   */
  startService: (appointmentId: string) =>
    http.post<TaskResult>(`/store/appointments/${appointmentId}/start-service`, undefined, { auth: true }),

  /**
   * 完成服务 `POST /store/appointments/{id}/finish-service`（ADR-048，闸 `task:finish`）。
   * 后端在**同一事务**里推进预约并写履约记录（洗护→ful_bath_record，遛狗→ful_walk_record，
   * 医疗类库里没有对应表 ⇒ 只推进状态、不编记录）。
   * `serviceItems` 传空数组是"一项都没勾"，传 undefined 是"没提交这个字段"，两者后端可区分。
   */
  finishService: (appointmentId: string, body: { serviceItems?: string[]; remark?: string }) =>
    http.post<TaskResult>(`/store/appointments/${appointmentId}/finish-service`, body, { auth: true }),
}

/**
 * 履约动作回执（ADR-048）。`changed=false` = 幂等重放（此前已达成），不是失败；
 * 页面据此换文案，不弹红色错误诱导用户再点一次。
 */
export interface TaskResult {
  appointmentId: string
  appointmentNo: string | null
  status: string
  changed: boolean
  employeeId: string | null
  storeServiceId: string | null
  actualStartAt: string | null
  actualEndAt: string | null
}

/** 签到回执。`status` 签到后恒 WAIT_SERVICE；`changed=false` = 此前已在待服务及之后（重放）。 */
export interface CheckinResult {
  appointmentId: string
  appointmentNo: string | null
  status: string
  changed: boolean
}

/**
 * 美容师负载 = 当天队列 × 本店员工名册，**在前端合成**（两份真数据，不新开端点）：
 * 逐人各查一次队列是 N+1，而后端聚合只是把这几行减法搬到服务端——门店量级下没有收益。
 * 队列已按权限收窄过（美容师只拿到本人任务），所以这里给非店长看的负载本来就是他的视野，
 * 页面按 storeWideScope 标注口径，不把"本人视野的计数"说成"全店负载"。
 *
 * 泛型约束只要求「有执行人 id + 状态」两个字段：洗护队列（BathingTask）与遛狗队列（WalkingTask，ADR-049）
 * 共用同一份负载算法，不为迁就遛狗页复制第二个 buildStaffLoad。
 */
export interface StaffTaskRef {
  employee?: { id: string } | null
  status: string
}

export function buildStaffLoad<T extends StaffTaskRef>(staff: EmployeeRow[], tasks: T[]): StaffLoad[] {
  return staff
    .filter((s) => s.status !== 'RESIGNED')
    .map((s) => {
      const mine = tasks.filter((t) => t.employee?.id === s.employeeId)
      return {
        employeeId: s.employeeId,
        name: s.name,
        roleCodes: s.roleCodes ?? [],
        onDuty: s.todayShift?.status === 'WORKING',
        scheduled: s.todayShift != null,
        onLeave: s.todayShift?.status === 'LEAVE',
        pending: mine.filter((t) => t.status === 'CONFIRMED' || t.status === 'WAIT_SERVICE').length,
        serving: mine.filter((t) => t.status === 'SERVING').length,
        done: mine.filter((t) => t.status === 'COMPLETED').length,
        cancelReview: mine.filter((t) => t.status === 'CANCEL_REQUESTED').length,
      }
    })
}

/**
 * 今日排班/在班聚合（纯函数）。三个指标口径不同，不可相互替代：
 * - `scheduled`＝今日有 `sch_schedule` 行的人数（请假行也算一次排班）；
 * - `onDuty`＝班次状态 WORKING 的人数；
 * - `onLeave`＝班次状态 LEAVE 的人数。
 *
 * ⚠ 模型是**一人一日一班**（V1 `uk_emp_date(employee_id, work_date)`），所以"同一员工一天多个独立班次"
 * 在当前库里不存在；若将来放开该唯一键，这里按 row 计数即可自然支持（本函数只数行，不按人去重之外的假设）。
 */
export interface StaffingSummary {
  members: number
  scheduled: number
  onDuty: number
  onLeave: number
}

export function staffingSummary(rows: ReadonlyArray<Pick<StaffLoad, 'scheduled' | 'onDuty' | 'onLeave'>>): StaffingSummary {
  return {
    members: rows.length,
    scheduled: rows.filter((r) => r.scheduled).length,
    onDuty: rows.filter((r) => r.onDuty).length,
    onLeave: rows.filter((r) => r.onLeave).length,
  }
}

/* ---- 写侧口径（ADR-042 签到 + ADR-048 履约三动作均已落地，实现见上方 bathingApi） ----
 * 与最初规划（P8 :217-218 / :230-231）的三处偏离，理由都记在 ADR-048：
 * ① 路径统一挂 `/store/appointments/{id}/…`（与 checkin 同族），不再另开 `/store/tasks/{id}/start|finish`
 *    前缀——状态机在 `apt_appointment` 这一张表上，两个前缀指向同一行只会让后来人猜哪边是真相。
 * ② 没有独立的 `records` 端点：履约记录与"完成服务"在同一个事务里写（缺任一边整体回滚），
 *    拆开就会出现"预约已完成但记录没写"的半截态。
 * ③ `task:accept`（接单）仍不实现：库里没有任何一列能表达"已接单但还没开始"，
 *    加列就是新状态；这枚码目前继续只当"看/动自己任务"的读侧闸用。
 * 仍未接入：`POST /store/appointments/{id}/cancel`（门店侧取消，牵 ADR-023 退款政策链，只在 C 端落地）、
 * 换台位（需要"给已确认单迁移占用"的业务裁决）。
 */

/* ---- 展示辅助 ---- */

export const BATH_QUEUE_STATUS_LABEL: Record<BathQueueStatus, string> = {
  CONFIRMED: '待签到',
  /** ADR-043：客户已提交取消申请，等门店在「订单管理 → 待处理退款」里审批；台位仍占着 */
  CANCEL_REQUESTED: '取消审核中',
  WAIT_SERVICE: '待服务',
  SERVING: '服务中',
  COMPLETED: '已完成',
}
export const BATH_QUEUE_STATUSES: BathQueueStatus[] = ['CONFIRMED', 'CANCEL_REQUESTED', 'WAIT_SERVICE', 'SERVING', 'COMPLETED']

export const SERVICE_TYPE_LABEL: Record<BathServiceType, string> = {
  BATH: '洗澡',
  GROOMING: '美容',
}

export const STATION_STATUS_LABEL: Record<string, string> = {
  AVAILABLE: '可用',
  MAINTENANCE: '维护中',
  DISABLED: '已停用',
}

/**
 * 服务要求的资源类型 → 中文（字典码取 V1:263 的 7 个值）。
 * 未知码原样回显：宁可看见生码，也不要把它错标成「不占台位」而让人以为无需排台。
 */
export function resourceTypeLabel(type: string | null): string {
  if (!type) return '无需台位'
  return ({
    BATH_STATION: '洗澡台',
    GROOMING_STATION: '美容台',
    EXAM_ROOM: '诊室',
    KENNEL_SMALL: '小型犬笼',
    KENNEL_MEDIUM: '中型犬笼',
    KENNEL_LARGE: '大型犬笼',
    CAT_ROOM: '猫舍',
  } as Record<string, string>)[type] ?? type
}

export function serviceItemLabel(code: string): string {
  return SKILL_LABEL[code as keyof typeof SKILL_LABEL] ?? code
}

/** 服务项目候选 = V2 种子的 emp_skill 8 码（读字典端点要 `catalog:skill:manage`，仅 ADMIN，故按字典码固定）。 */
export const SERVICE_ITEM_CODES = ['BATH', 'HAIRCUT', 'NAIL_CARE', 'EAR_CLEAN', 'WALK', 'BOARDING_CARE', 'MEDICAL_EXAM', 'VACCINATION']
export const SERVICE_ITEM_OPTIONS = SERVICE_ITEM_CODES.map((code) => ({ code, label: serviceItemLabel(code) }))

/** 30 分钟槽位号 → 「10:00」；slot_no = 小时*2 + 分钟/30（ADR-005）。 */
export function slotToTime(slot: number): string {
  const h = Math.floor(slot / 2)
  const m = slot % 2 === 0 ? '00' : '30'
  return `${String(h).padStart(2, '0')}:${m}`
}

/** 时刻：后端 LocalTime 出参已是「HH:mm」（JacksonConfig），无值给破折号；兼容「HH:mm:ss」。 */
export function shortTime(t: string | null): string {
  return t ? t.slice(0, 5) : '—'
}

/** 「HH:mm」→ 30 分钟槽位号（slotToTime 的逆）：只吃前 5 位「HH:mm」，解析不动返回 null。 */
export function timeToSlot(t: string | null): number | null {
  if (!t) return null
  const m = /^(\d{1,2}):(\d{2})/.exec(t.slice(0, 5))
  if (!m) return null
  return Number(m[1]) * 2 + Math.floor(Number(m[2]) / 30)
}

/** 日期时间：LocalDateTime 出参是 ISO-8601「yyyy-MM-ddTHH:mm:ss」→「yyyy-MM-dd HH:mm」；无值给破折号。 */
export function dateTimeText(iso: string | null): string {
  if (!iso) return '—'
  return iso.includes('T') ? `${iso.slice(0, 10)} ${iso.slice(11, 16)}` : iso
}

/** 队列行的时段文案：起止齐全给区间，只有开始给「HH:MM 起」，全无给「时间待定」。 */
export function taskTimeText(t: BathingTask): string {
  if (!t.startTime) return '时间待定'
  if (!t.endTime) return `${shortTime(t.startTime)} 起`
  return `${shortTime(t.startTime)}–${shortTime(t.endTime)}`
}

/** 服务时长文案：60 → 「1 小时」，90 → 「1 小时 30 分」；null 如实说明目录未配时长。 */
export function durationText(min: number | null): string {
  if (min == null) return '时长未配置'
  const h = Math.floor(min / 60)
  const m = min % 60
  if (h === 0) return `${m} 分钟`
  return m === 0 ? `${h} 小时` : `${h} 小时 ${m} 分`
}

/** 展示时段：只画 08:00–20:00（slot 16..40），凌晨与深夜没人洗澡不必占屏。 */
export const VISIBLE_SLOT_START = 16
export const VISIBLE_SLOT_END = 40

/** 本地日期（不用 toISOString，避免 UTC 偏移把「今天」变成昨天）。 */
export function dateOfOffset(off: number): string {
  const d = new Date()
  d.setDate(d.getDate() + off)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/**
 * 客户端过滤（端点一次给完当天两类全量，所以页签计数是真数，不是"已取回的条数冒充全店数"）。
 * mineOnly 直接用服务端的 `assignedToMe`（它拿 principal.employeeId 与行的执行人比对），前端不猜权限、也不自带身份参数。
 */
export function filterTasks(
  list: BathingTask[],
  f: { status?: BathQueueStatus; type?: BathServiceType; mineOnly?: boolean },
): BathingTask[] {
  return list
    .filter((t) => !f.status || t.status === f.status)
    .filter((t) => !f.type || t.serviceType === f.type)
    .filter((t) => !f.mineOnly || t.assignedToMe === true)
    .sort((a, b) => (a.startTime ?? '99').localeCompare(b.startTime ?? '99'))
}

export function tabCounts(list: BathingTask[]): Record<'ALL' | BathQueueStatus, number> {
  const n = (s: BathQueueStatus) => list.filter((t) => t.status === s).length
  return {
    ALL: list.length,
    CONFIRMED: n('CONFIRMED'),
    CANCEL_REQUESTED: n('CANCEL_REQUESTED'),
    WAIT_SERVICE: n('WAIT_SERVICE'),
    SERVING: n('SERVING'),
    COMPLETED: n('COMPLETED'),
  }
}

export function typeCounts(list: BathingTask[]): Record<BathServiceType, number> {
  return { BATH: list.filter((t) => t.serviceType === 'BATH').length, GROOMING: list.filter((t) => t.serviceType === 'GROOMING').length }
}

/** 某台位在指定槽是否被占（占用条与任务时间轴共用同一份 occupiedSlots，不留第二处真相）。 */
export function slotOccupied(station: BathStation, slot: number): boolean {
  return station.occupiedSlots.includes(slot)
}
