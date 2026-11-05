import { http } from './client'
import { BizCode, BizError } from './types'

/**
 * B 端员工管理接口（ADR-024 已收口契约，**直连真实后端**，不走演示兜底）。
 *
 * 三条硬约束（来自 DDL 与 ADR，不是风格选择）：
 * 1. 员工表无手机号列 → `phoneMasked` 是 user 模块派生并脱敏后的值；
 * 2. 角色带门店维度（emp_employee_role.store_id，ADR-004）→ `roleCodes` 只表示**当前工作门店**的角色；
 * 3. 「在班」只由 sch_schedule 决定（ADR-004）→ `todayShift` 来自当日排班行，与 `status` 是两回事。
 *
 * 脚注④字段裁剪（ADR-024 §2）：只持 `emp:view` 的角色（前台）拿不到
 * `phoneMasked / hiredAt / leftAt / skillCodes / affiliations / activeStoreCount`，
 * 这些字段是 **null = 按权限不披露**，不是"没有值"——UI 必须区分这两种情况。
 */

export type EmployeeStatus = 'ACTIVE' | 'ON_LEAVE' | 'SUSPENDED' | 'RESIGNED'
/** 平台侧可写的全局状态（三值白名单；ON_LEAVE 全仓无消费方，不发明口径） */
export type EmployeeGlobalStatus = 'ACTIVE' | 'SUSPENDED' | 'RESIGNED'
export type ScheduleStatus = 'WORKING' | 'LEAVE'
export type LeaveStatus = 'NONE' | 'APPLYING' | 'APPROVED'
export type EmployeeTab = 'ALL' | 'ON_DUTY' | 'LEAVE' | 'IDLE' | 'OFF'

export interface EmployeeShift {
  scheduleId: string
  workDate: string
  startTime: string | null
  endTime: string | null
  status: ScheduleStatus
  leaveStatus: LeaveStatus
  /** 恒 null：ScheduleDayView 未导出 leave_reason（ADR-024 §4） */
  leaveReason: string | null
}

export interface StoreAffiliation {
  storeId: string
  /** store 段补值前可能为 null，显示层回退「门店 {id}」，不编店名 */
  storeName: string | null
  isPrimary: boolean
  status: 'ACTIVE' | 'ENDED'
  startedAt: string | null
  endedAt: string | null
  roleCodes: string[]
}

export interface EmployeeRow {
  employeeId: string
  name: string
  employeeNo: string | null
  status: EmployeeStatus
  hiredAt: string | null
  leftAt: string | null
  phoneMasked: string | null
  /** 当前工作门店下的角色 */
  roleCodes: string[]
  rolesLabel: string | null
  /** null = 按脚注④不披露 */
  skillCodes: string[] | null
  todayShift: EmployeeShift | null
  affiliations: StoreAffiliation[] | null
  activeStoreCount: string | null
  /**
   * **当前请求门店**的归属状态（ACTIVE/ENDED），服务端按 EXISTS 语义算好。
   * 不受脚注④裁剪（前台 emp:view 也拿得到）——这是列表区分"本店在册 / 已移出本店"、
   * 统计只算本店在职者的唯一可靠依据。字段缺失（旧后端未部署）时为 undefined，绝不臆断为 ACTIVE。
   */
  currentStoreAffiliation?: 'ACTIVE' | 'ENDED' | null
}

export interface EmployeeListParams {
  tab?: EmployeeTab
  keyword?: string
}

/** 后端 GET /store/schedules 的行（字段名照 StoreScheduleController.ScheduleBoardItem）。 */
interface RawScheduleItem {
  id: string
  employeeId: string
  employeeName: string | null
  roleCodes: string[]
  workDate: string
  startTime: string | null
  endTime: string | null
  status: string
  leaveStatus: string
}

/**
 * 店长可授的 5 个员工型角色（ADR-001-E：店长可分配**除 STORE_MANAGER 外**的员工型角色）。
 * STORE_MANAGER 只能由平台 ADMIN 经 `POST /admin/employees/{id}/roles?storeId=` 授予——防提权链（店长自我复制）。
 * CUSTOMER/ADMIN 是平台维度角色，任何端点都不经此处授。
 */
export const STORE_ASSIGNABLE_ROLES = ['DOCTOR', 'GROOMER', 'DOG_WALKER', 'CARETAKER', 'RECEPTIONIST'] as const
/** 只有平台侧能授的角色（UI 里置灰并写明原因，而不是悄悄不给看） */
export const ADMIN_ONLY_ROLES = ['STORE_MANAGER'] as const

/** 写侧回执（ADR-033）。两个 created 标记是这份回执的价值：店长要能分清"建了档 / 只加进本店 / 本来就在"。 */
export interface EmployeeManageResult {
  employeeId: string
  userId: string
  /** 当前生效姓名：新建=来料；已建档=库里那份（本店覆盖不了他店的档） */
  name: string
  employeeNo: string | null
  storeId: string
  employeeCreated: boolean
  affiliationCreated: boolean
  primaryStore: boolean
  roleCodes: string[] | null
  skillCodes: string[] | null
}

export interface OnboardEmployeeBody {
  /** 必须是**已注册**手机号（B 端注册需短信验证码，店长不能替设密码） */
  phone: string
  name: string
  employeeNo?: string
  /** yyyy-MM-dd，缺省=今日 */
  hiredAt?: string
  /** 至少一个；多角色=同一门店多行（uk_emp_role_store 天然支持兼岗） */
  roleCodes: string[]
}

/** 排班行（后端 ScheduleBrief）。带 storeId 是为了在删班前知道这是不是本店能动的行。 */
export interface ShiftBrief {
  id: string
  employeeId: string
  storeId: string
  workDate: string
  startTime: string | null
  endTime: string | null
  status: ScheduleStatus
}

export interface BatchGenerateBody {
  /** 1..50 名；雪花 id 一律按字符串传，数字会在 2^53 之外丢精度 */
  employeeIds: string[]
  /** yyyy-MM-dd，且不得早于今日 */
  startDate: string
  endDate: string
  /** HH:mm */
  startTime: string
  endTime: string
  /** ISO 星期 1(周一)~7(周日)；省略 = 区间内每天都排 */
  weekdays?: number[]
}

export interface BatchGenerateResult {
  created: ShiftBrief[]
  /**
   * 因当日已有班而未被触碰的日子。`storeId` 是**已有那条班**所属的门店（可能不是本店）——
   * 这是系统里唯一能把"这人被别的店占了"说给店长听的时刻，读侧接口根本不导出 storeId。
   */
  skipped: { employeeId: string; workDate: string; storeId: string | null }[]
}

export const WEEKDAY_LABEL: Record<number, string> = {
  1: '一',
  2: '二',
  3: '三',
  4: '四',
  5: '五',
  6: '六',
  7: '日',
}

/** 后端 StoreScheduleController.ScheduleBoardItem 的排班周期上限（前端提示要对齐，别让人排到 60 天后）。 */
export const SCHEDULE_MAX_SPAN_DAYS = 31

export const employeesApi = {
  /**
   * `GET /store/employees`（P8 :225，`emp:view`，ADR-024 §1）。
   * tab/keyword 服务端过滤；**不分页**（单店员工量级小）；页签计数无端点 → 页面不显示数字。
   */
  list: (p: EmployeeListParams = {}) => {
    const q = new URLSearchParams()
    if (p.tab && p.tab !== 'ALL') q.set('tab', p.tab)
    if (p.keyword) q.set('keyword', p.keyword)
    return http.get<EmployeeRow[]>(`/store/employees?${q.toString()}`, { auth: true })
  },

  /**
   * 某员工未来 7 天排班（P8 :220，双码 `schedule:self:view`/`schedule:manage`，区间 ≤31 天）。
   * 只持 `schedule:self:view` 的角色（前台/任务角色）会被服务端强制只看本人 → 看他人排班恒为空，属权限口径。
   */
  weeklyShifts: async (employeeId: string): Promise<EmployeeShift[]> => {
    const from = localDate(0)
    const raw = await http.get<RawScheduleItem[]>(
      `/store/schedules?employeeId=${employeeId}&startDate=${from}&endDate=${localDate(6)}`,
      { auth: true },
    )
    return raw.map(toShift)
  },

  /* ---------- 写侧（ADR-033，P8 §4.3 规划的 /store/employees 半边） ---------- */

  /** 纳编：手机号未注册 → 10004（文案会引导先注册）；已在别店建档 → 只加本店归属，不改他店姓名。 */
  onboard: (body: OnboardEmployeeBody) =>
    http.post<EmployeeManageResult>('/store/employees', body, { auth: true }),

  /** 全量替换本店角色（codes 为空数组 = 清空本店角色）。 */
  assignRoles: (employeeId: string, codes: string[]) =>
    http.post<EmployeeManageResult>(`/store/employees/${employeeId}/roles`, { codes }, { auth: true }),

  /**
   * 平台侧全量替换某员工在**指定门店**的角色（ADR-033 补口；闸 `store:manage`，V2 种子实测仅 ADMIN 持有）。
   * 与店长端 `assignRoles` 的唯一区别：白名单放开到含 STORE_MANAGER（可任命/撤销店长），且 storeId 显式给出。
   * `storeId` 必须来自当前合法操作上下文（storeContext），不得拼造或接受未校验的任意门店值；
   * 目标员工须在该店有 ACTIVE 归属（否则后端 10004），撤销本店最后一名店长由服务端 ADR-035 守卫拒绝（10003）。
   * X-Store-Id 由 client 注入当前上下文，与本参数同源（平台管理员代入的门店即目标门店）。
   */
  adminAssignRoles: (employeeId: string, storeId: string, codes: string[]) =>
    http.post<EmployeeManageResult>(
      `/admin/employees/${employeeId}/roles?storeId=${encodeURIComponent(storeId)}`,
      { codes },
      { auth: true },
    ),

  /** 全量替换技能。技能有真实消费方：预约页的员工候选要求技能超集（ADR-016 §7），授完立刻影响可指派名单。 */
  assignSkills: (employeeId: string, codes: string[]) =>
    http.put<EmployeeManageResult>(`/store/employees/${employeeId}/skills`, { codes }, { auth: true }),

  /**
   * 平台侧调整技能（ADR-051，闸 store:manage 仅 ADMIN）。ADR-040 把技能写权收归主店店长，
   * 但主店可能是没有店长的总部虚拟店 → 写权悬空，全系统无人能改。本通道以员工主店为语境
   * 复用店长替换逻辑（守卫对主店自己恒放行），主店为空 fail-open——任何代入店上下文均可调用。
   */
  adminUpdateSkills: (employeeId: string, codes: string[]) =>
    http.put<EmployeeManageResult>(`/admin/employees/${employeeId}/skills`, { codes }, { auth: true }),

  /** 结束本店归属（店级动作）。全局在职/离职状态不在此处改——那是跨店共享字段，属平台段。 */
  leaveStore: (employeeId: string) =>
    http.post<EmployeeManageResult>(`/store/employees/${employeeId}/leave-store`, undefined, { auth: true }),

  /**
   * 平台侧编辑员工全局档案（ADR-050，PUT 全量提交：employeeNo/hiredAt 传 null=清空该列）。
   * 闸 store:manage（实测仅 ADMIN）——姓名/工号/入职日是 emp_employee 一行上的跨店共享字段，
   * 店长改了所有门店看到的都是新值，两店互改必打架（ADR-033 §2），所以只有平台管理员（含 ADR-044 代入态）可用。
   * 刻意不含在职/离职状态：停用要联动清会话踢下线，是平台停用端点的独立裁决。
   */
  updateProfile: (employeeId: string, body: { name: string; employeeNo?: string | null; hiredAt?: string | null }) =>
    http.put<EmployeeManageResult>(`/admin/employees/${employeeId}/profile`, body, { auth: true }),

  /**
   * 平台侧变更员工全局状态（停用 / 离职 / 恢复；闸 store:manage 仅 ADMIN）。
   * 服务端在同一笔里联动权限缓存失效与登录态踢出，故对 B 端的冻结是即时的（不用等 access 过期）；
   * C 端顾客身份不受影响（账号与员工档案是两码事）。
   */
  adminUpdateStatus: (employeeId: string, status: EmployeeGlobalStatus) =>
    http.put<EmployeeManageResult>(`/admin/employees/${employeeId}/status`, { status }, { auth: true }),

  /* ---------- 排班写侧（ADR-034，P8 §4.3 :221；与上面的 weeklyShifts 同一路径资源） ---------- */

  /**
   * 批量排班。撞 `uk_emp_date` 的日子**不会被触碰**，而是回在 `skipped` 里并注明那班属于哪家店——
   * 一人一天全平台只有一个班，覆盖他店的班属于跨店互吞（ADR-034 §2）。
   */
  generateShifts: (body: BatchGenerateBody) =>
    http.post<BatchGenerateResult>('/store/schedules/batch-generate', body, { auth: true }),

  /**
   * 删掉一条班。该员工当天还有进行中的预约时后端返 10001 并拒绝——先改派客人，再来调班。
   * 请假（status=LEAVE）不在此处：表模型是"有行才上班"，要让人休息就删那天的班。
   */
  deleteShift: (scheduleId: string) =>
    http.delete<void>(`/store/schedules/${scheduleId}`, { auth: true }),
}

/** 后端 GET /store/schedules 的返回体（字段名照 StoreScheduleController.ScheduleBoardItem）。 */
function toShift(r: RawScheduleItem): EmployeeShift {
  return {
    scheduleId: r.id,
    workDate: r.workDate,
    startTime: r.startTime,
    endTime: r.endTime,
    status: r.status === 'LEAVE' ? 'LEAVE' : 'WORKING',
    leaveStatus: r.leaveStatus === 'APPROVED' ? 'APPROVED' : r.leaveStatus === 'APPLYING' ? 'APPLYING' : 'NONE',
    leaveReason: null,
  }
}

/** 本地历法日期（禁 toISOString：UTC 偏移会让"今天"在凌晨跳错一天）。 */
export function localDate(offsetDays = 0): string {
  const d = new Date()
  d.setDate(d.getDate() + offsetDays)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/* ---- 字典与文案（V2 种子真实值：emp_role 101-108、emp_skill 301-308） ---- */

export const ROLE_LABEL: Record<string, string> = {
  CUSTOMER: '宠物主人',
  ADMIN: '平台管理员',
  STORE_MANAGER: '门店店长',
  DOCTOR: '宠物医生',
  GROOMER: '洗澡美容师',
  DOG_WALKER: '遛狗师',
  CARETAKER: '护理托管员',
  RECEPTIONIST: '前台',
}

export const SKILL_LABEL: Record<string, string> = {
  BATH: '洗澡',
  HAIRCUT: '剪毛造型',
  NAIL_CARE: '指甲护理',
  EAR_CLEAN: '耳道清洁',
  WALK: '遛狗',
  BOARDING_CARE: '托管护理',
  MEDICAL_EXAM: '诊疗',
  VACCINATION: '疫苗接种',
}

export const ALL_SKILL_CODES = Object.keys(SKILL_LABEL)

export const EMPLOYEE_STATUS_LABEL: Record<EmployeeStatus, string> = {
  ACTIVE: '在职',
  ON_LEAVE: '请假中',
  SUSPENDED: '已停用',
  RESIGNED: '已离职',
}

export const EMPLOYEE_TAB_LABEL: Record<EmployeeTab, string> = {
  ALL: '全部员工',
  ON_DUTY: '今日在班',
  LEAVE: '今日请假',
  IDLE: '今日未排班',
  OFF: '停用离职',
}

/** 昵称首字（中日韩取第 1 字，拉丁取大写首字母）——头像占位用，库里无头像列。 */
export function employeeInitial(name: string): string {
  const ch = name.trim().charAt(0) || '员'
  return /[a-zA-Z]/.test(ch) ? ch.toUpperCase() : ch
}

export function roleLabelsOf(codes: string[]): string {
  return codes.length ? codes.map((c) => ROLE_LABEL[c] ?? c).join('·') : '未分配角色'
}

export const LEAVE_STATUS_LABEL: Record<LeaveStatus, string> = {
  NONE: '请假',
  APPLYING: '请假待审批',
  APPROVED: '已批准',
}

/** 班次文案：WORKING 给时段，LEAVE 给审批态，无行给未排班。 */
export function shiftText(s: EmployeeShift | null): string {
  if (!s) return '未排班'
  if (s.status === 'LEAVE') return LEAVE_STATUS_LABEL[s.leaveStatus]
  return s.startTime && s.endTime ? `${s.startTime.slice(0, 5)}–${s.endTime.slice(0, 5)}` : '在班（时段未定）'
}

export function isOffDuty(r: EmployeeRow): boolean {
  return r.status === 'RESIGNED' || r.status === 'SUSPENDED'
}

/**
 * 当前请求门店归属三态。`ACTIVE`/`ENDED` 来自服务端算好的 `currentStoreAffiliation`；
 * `UNKNOWN` = 字段缺失（旧后端未下发），此时**不得**臆断为在册——宁可少算，不把已移出本店者算进来。
 * 注意：这里**不看** `activeStoreCount`（那是"在职门店总数"，与"本店有没有他"无关），也不看被裁剪的 `affiliations`。
 */
export type CurrentStoreAffiliation = 'ACTIVE' | 'ENDED' | 'UNKNOWN'

export function currentStoreAffiliationOf(r: EmployeeRow): CurrentStoreAffiliation {
  return r.currentStoreAffiliation === 'ACTIVE' || r.currentStoreAffiliation === 'ENDED'
    ? r.currentStoreAffiliation
    : 'UNKNOWN'
}

/**
 * 当前门店「在职统计」口径：全局 `status === 'ACTIVE'` ∧ 本店归属 `ACTIVE` 同时成立才计入。
 * 直接用全局 ACTIVE 判据，**不以 `!isOffDuty` 代劳**——故 ON_LEAVE（请假中）不计入当前在职人数与技能覆盖；
 * 但本谓词只闸统计，**不删名册记录、不改 `isOffDuty` 语义**：ON_LEAVE 仍在名册可见、详情仍显示「请假中」，
 * 其「本店归属」标签仍按归属轴照常判定。
 * 同样排除：仅本店 ENDED（已移出）、UNKNOWN（无归属信号）、SUSPENDED、RESIGNED。
 * 不读 `activeStoreCount`，也不读被脚注④裁剪的 `affiliations`。
 */
export function isCurrentStoreMember(r: EmployeeRow): boolean {
  return r.status === 'ACTIVE' && currentStoreAffiliationOf(r) === 'ACTIVE'
}

/**
 * 「今日请假」独立口径——**不复用** `isCurrentStoreMember`（后者按在职统计刻意排除了 ON_LEAVE）。
 * 计入条件：本店归属 `ACTIVE`（归属 ENDED/UNKNOWN 的请假者不算本店今日请假）∧ 全局非停用/离职（`!isOffDuty`），
 * 且命中请假语义之一：全局 `status === 'ON_LEAVE'`，或今日有 `LEAVE` 班次（保留既有：ACTIVE 成员今天请了班也计入）。
 * 每个员工只判一次，状态来源与班次来源同时命中也不会重复计数。
 * 仅服务"今日请假"这一个指标——不改变在职人数/在岗/未排班/技能覆盖对 `isCurrentStoreMember` 的依赖。
 */
export function isOnLeaveTodayInStore(r: EmployeeRow): boolean {
  if (currentStoreAffiliationOf(r) !== 'ACTIVE') return false
  if (isOffDuty(r)) return false
  return r.status === 'ON_LEAVE' || r.todayShift?.status === 'LEAVE'
}

/**
 * 技能是否可见：`skillCodes` 为 null 表示按脚注④被裁剪（无 emp:update），
 * 不是"该员工没技能"。技能覆盖统计必须据此整体降级为"不可见"，绝不能把 null 当空数组算成"无人可服务"。
 */
export function skillsVisible(r: EmployeeRow): boolean {
  return r.skillCodes !== null && r.skillCodes !== undefined
}

/** 列表行「本店归属」标签（与全局状态徽章分开显示，避免用全局 ACTIVE 冒充"本店在职"）。 */
export function storeMembershipLabel(r: EmployeeRow): string {
  const aff = currentStoreAffiliationOf(r)
  if (aff === 'ACTIVE') return '本店在册'
  if (aff === 'ENDED') return '已移出本店'
  return '本店归属未知'
}

/**
 * 批量排班资格＝服务端 `isActiveInStore` 的同口径前端判据：全局 `status==='ACTIVE'` ∧ 当前门店存在 ACTIVE 归属。
 * 与 `ScheduleCommandService.batchGenerate` 的整批拒绝闸一致——避免前端把无效员工塞进候选导致整批被拒。
 *
 * 归属判据用「存在性」而非取第一条：同一门店可能同时有历史 ENDED 行与当前 ACTIVE 行（离职返聘/调店回迁），
 * 只要有一条 ACTIVE 归属即算在册（与 `countActiveInStore` 的 `EXISTS es.status='ACTIVE'` 同构）。
 * **不用 `activeStoreCount`**：它只表示在职门店数量，无法证明「当前所选门店」的归属状态。
 *
 * `affiliations` 为 null（脚注④：仅持 emp:view 的前台被裁剪）时资格不可判定 → 视为不合格并给出明确原因，
 * 不凭全局状态臆断「可排班」。持有 schedule:manage 的角色（店长/平台管理员）必带 emp:update，故 affiliations 恒在。
 */
export function scheduleIneligibleReason(r: EmployeeRow, storeId: string | null): string | null {
  if (storeId === null) return '缺少门店上下文，无法排班'
  if (r.status === 'RESIGNED') return '该员工已离职，不可排班'
  if (r.status === 'SUSPENDED') return '该员工已停用，不可排班'
  if (r.status === 'ON_LEAVE') return '该员工请假中，不可排班'
  if (r.status !== 'ACTIVE') return '该员工当前状态不可排班'
  if (!r.affiliations) return '门店归属信息不可见，无法确认排班资格'
  if (!r.affiliations.some((a) => a.storeId === storeId && a.status === 'ACTIVE')) {
    return '该员工已不在本店（本店归属已结束），不可排班'
  }
  return null
}

/** 是否可进入批量排班候选 / 作为排班 preset：`scheduleIneligibleReason` 为 null 即合格。 */
export function isScheduleEligible(r: EmployeeRow, storeId: string | null): boolean {
  return scheduleIneligibleReason(r, storeId) === null
}

/** STORE_MANAGER 角色码（ADR-001-E：只能由平台 ADMIN 经 /admin 端点授予/撤销，店长端白名单不含它）。 */
export const STORE_MANAGER_ROLE = 'STORE_MANAGER'

/** 目标员工在**本店**是否已是店长（roleCodes 为本店维度，含 STORE_MANAGER 即是）。 */
export function employeeIsManager(roleCodes: string[]): boolean {
  return roleCodes.includes(STORE_MANAGER_ROLE)
}

export type RoleSubmitTarget =
  | { kind: 'blocked' }
  | { kind: 'admin'; storeId: string }
  | { kind: 'store' }

/**
 * 提交前按操作者身份决定角色写端点（ADR-001-E 防提权链）。`roleCodes` 是目标员工**当前本店**角色：
 * - 非平台操作者面对店长员工 → `blocked`：店长端白名单不含 STORE_MANAGER，照常提交必被 10003 拒；
 *   若过滤掉 STORE_MANAGER 再提交，全量替换语义会**误撤销**其店长角色。故锁定，不发注定失败/有害的请求。
 * - 平台管理员且门店上下文可用 → `admin`：走平台端点（白名单含 STORE_MANAGER），可任命/撤销店长。
 * - 其余 → `store`：原有门店端全量替换（5 个员工型角色），行为不变。
 */
export function resolveRoleSubmit(
  platformAdmin: boolean,
  storeId: string | null,
  roleCodes: string[],
): RoleSubmitTarget {
  if (!platformAdmin && employeeIsManager(roleCodes)) {
    return { kind: 'blocked' }
  }
  if (platformAdmin && storeId) {
    return { kind: 'admin', storeId }
  }
  return { kind: 'store' }
}

/** 抛错文案统一：本页所有区块共用，10003 要能区分出"权限口径"而非故障。 */
export function employeeErrorText(err: unknown): { message: string; denied: boolean } {
  if (err instanceof BizError) {
    return { message: err.message, denied: err.code === BizCode.FORBIDDEN }
  }
  return { message: '加载失败，请重试', denied: false }
}
