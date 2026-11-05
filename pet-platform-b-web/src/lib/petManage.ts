import { BizCode } from '../api/types'
import type { CareRecord } from '../api/boarding'
import type {
  PetHealthChange,
  PetHealthDetail,
  PetHealthSnapshot,
  PetHealthUpdateBody,
  PetProfile,
  PetServiceRecord,
  PetSpecies,
  PetStoreUpdateBody,
} from '../api/pets'
import type { Tone } from '../mock/dashboard'

export type PetTab = 'ALL' | PetSpecies

export interface PetDraft {
  name: string
  species: PetSpecies
  breed: string
  gender: 'MALE' | 'FEMALE' | 'UNKNOWN'
  birthday: string
  weightKg: string
  remark: string
}

export type PetDraftErrors = Partial<Record<keyof PetDraft, string>>

export interface PetCreateBody {
  name: string
  species: PetSpecies
  breed: string | null
  gender: 'MALE' | 'FEMALE' | 'UNKNOWN' | null
  birthday: string | null
  weightKg: number | null
  remark: string | null
}

export const EMPTY_PET_DRAFT: PetDraft = {
  name: '',
  species: 'DOG',
  breed: '',
  gender: 'UNKNOWN',
  birthday: '',
  weightKg: '',
  remark: '',
}

/** 本地历法日期，禁止 toISOString（会跨时区偏一天）。 */
export function todayISO(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function validDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const d = new Date(`${value}T00:00:00`)
  return !Number.isNaN(d.getTime())
}

/** 与 B 端现有新增宠物表单及 PetCreateRequest 对齐的客户端校验。 */
export function validatePetDraft(draft: PetDraft, today = todayISO()): PetDraftErrors {
  const errors: PetDraftErrors = {}
  const name = draft.name.trim()
  if (!name) errors.name = '请输入宠物昵称'
  else if (name.length > 50) errors.name = '宠物昵称不超过 50 字'

  if (draft.breed.trim().length > 50) errors.breed = '品种不超过 50 字'
  if (!['DOG', 'CAT', 'OTHER'].includes(draft.species)) errors.species = '请选择物种'
  if (!['MALE', 'FEMALE', 'UNKNOWN'].includes(draft.gender)) errors.gender = '请选择性别'

  if (draft.birthday) {
    if (!validDate(draft.birthday)) errors.birthday = '生日格式不正确'
    else if (draft.birthday > today) errors.birthday = '生日不能晚于今天'
  }

  if (draft.weightKg.trim()) {
    const weight = Number(draft.weightKg)
    if (!Number.isFinite(weight) || weight <= 0) errors.weightKg = '体重需为大于 0 的数字（kg）'
    else if (weight > 999.99) errors.weightKg = '体重超出可录入范围（≤999.99kg）'
  }

  if (draft.remark.trim().length > 500) errors.remark = '备注不超过 500 字'
  return errors
}

/** 只提交后端 PetCreateRequest 支持的字段；不夹带 sizeCategory/vaccineSummary/status。 */
export function buildPetCreateBody(draft: PetDraft): PetCreateBody {
  const weight = draft.weightKg.trim()
  return {
    name: draft.name.trim(),
    species: draft.species,
    breed: draft.breed.trim() || null,
    gender: draft.gender || null,
    birthday: draft.birthday || null,
    weightKg: weight ? Number(weight) : null,
    remark: draft.remark.trim() || null,
  }
}

/** 新建宠物与当前物种页签不匹配时回到全部，避免创建成功后“找不到刚建的宠物”。 */
export function resolveTabAfterCreate(currentTab: PetTab, species: PetSpecies): PetTab {
  return currentTab === 'ALL' || currentTab === species ? currentTab : 'ALL'
}

export interface PetCreateSuccessPlan {
  tab: PetTab
  /** 页签不变时需要显式刷新；页签变化会由 useAsync 的 reloadKey 自动重拉 */
  refreshList: boolean
  refreshOverview: boolean
}

export function planAfterPetCreated(currentTab: PetTab, species: PetSpecies): PetCreateSuccessPlan {
  const nextTab = resolveTabAfterCreate(currentTab, species)
  return { tab: nextTab, refreshList: nextTab === currentTab, refreshOverview: true }
}

export function planAfterPetUpdated(currentTab: PetTab, species: PetSpecies): PetCreateSuccessPlan {
  return planAfterPetCreated(currentTab, species)
}

/* ---- B 端受限基础资料编辑 ---- */

export interface PetEditDraft {
  name: string
  species: PetSpecies
  breed: string
  /** 空字符串=未登记/不修改；提交为 null，服务端保留原值 */
  gender: 'MALE' | 'FEMALE' | 'UNKNOWN' | ''
  isNeutered: boolean | null
  birthday: string
  birthdayApprox: boolean
  weightKg: string
  remark: string
}

export type PetEditErrors = Partial<Record<keyof PetEditDraft, string>>

export function toPetEditDraft(pet: PetProfile): PetEditDraft {
  return {
    name: pet.name,
    species: pet.species,
    breed: pet.breed ?? '',
    gender: pet.gender ?? '',
    isNeutered: pet.isNeutered ?? null,
    birthday: pet.birthday ?? '',
    birthdayApprox: pet.birthdayApprox ?? false,
    weightKg: pet.weightKg == null ? '' : String(pet.weightKg),
    remark: pet.remark ?? '',
  }
}

export function validatePetEditDraft(draft: PetEditDraft, today = todayISO()): PetEditErrors {
  // 核心字段规则与新增宠物共用；gender 为空表示“保留原值”，客户端不额外造值
  return validatePetDraft(
    {
      name: draft.name,
      species: draft.species,
      breed: draft.breed,
      gender: draft.gender || 'UNKNOWN',
      birthday: draft.birthday,
      weightKg: draft.weightKg,
      remark: draft.remark,
    },
    today,
  )
}

/** 只提交 B 端受限更新 DTO 的字段；绝不带 avatar/status/owner/sizeCategory/vaccineSummary。 */
export function buildPetEditBody(draft: PetEditDraft): PetStoreUpdateBody {
  const weight = draft.weightKg.trim()
  return {
    name: draft.name.trim(),
    species: draft.species,
    breed: draft.breed.trim() || null,
    gender: draft.gender || null,
    isNeutered: draft.isNeutered,
    birthday: draft.birthday || null,
    birthdayApprox: draft.birthday ? draft.birthdayApprox : false,
    weightKg: weight ? Number(weight) : null,
    remark: draft.remark.trim() || null,
  }
}

export function canEditPet(platformAdmin: boolean, roles: readonly string[] | null | undefined): boolean {
  return canCreatePet(platformAdmin, roles)
}

/**
 * B 端删除沿用现有 B 宠物写权限 customer:update（店长/前台/ADMIN）。
 * 本仓没有专用 B 删除权限码；后端仍以同一码 + pet:view 裁决。
 */
export function canDeletePet(platformAdmin: boolean, roles: readonly string[] | null | undefined): boolean {
  return canCreatePet(platformAdmin, roles)
}

export function petDeleteErrorText(code: number, message: string): string {
  if (code === PET_FORBIDDEN_CODE) return '该宠物当前不可见或已被删除'
  if (code === BizCode.FORBIDDEN) return '当前账号无删除宠物权限（customer:update）'
  if (code === BizCode.ILLEGAL_STATE_TRANSITION) {
    return message || '该宠物仍有进行中或未完成的预约或托管，暂时无法删除'
  }
  if (code === BizCode.NOT_FOUND) return '该宠物不存在或不属于当前门店'
  return message || '删除失败，请稍后重试'
}

export function petEditErrorText(code: number, message: string): string {
  if (code === PET_FORBIDDEN_CODE) return '该宠物当前不可见或已被删除'
  if (code === BizCode.FORBIDDEN) return '当前账号无编辑宠物权限（customer:update）'
  if (code === BizCode.NOT_FOUND) return '该宠物的归属客户不在本店客户集，无法编辑'
  return message || '保存失败，请稍后重试'
}

/** 与 V8 的 customer:update 授予面一致：店长、前台、ADMIN。 */
export function canCreatePet(platformAdmin: boolean, roles: readonly string[] | null | undefined): boolean {
  if (platformAdmin) return true
  return !!roles?.some((role) => role === 'STORE_MANAGER' || role === 'RECEPTIONIST' || role === 'ADMIN')
}

/**
 * 健康档案读权限的前端可判定镜像：V2 矩阵中 DOG_WALKER 没有 pet:health:view；
 * 其余 B 端角色（店长/前台/医生/美容师/护理员/ADMIN）可读。最终仍由后端 10003 裁决。
 */
export function canViewPetHealth(platformAdmin: boolean, roles: readonly string[] | null | undefined): boolean {
  if (platformAdmin) return true
  return !!roles?.some((role) =>
    role === 'ADMIN'
    || role === 'STORE_MANAGER'
    || role === 'RECEPTIONIST'
    || role === 'DOCTOR'
    || role === 'GROOMER'
    || role === 'CARETAKER')
}

/**
 * 健康档案写权限的前端可判定镜像：V2 矩阵中持有 pet:health:manage 的门店角色
 * 是 ADMIN / DOCTOR / CARETAKER；STORE_MANAGER 只有 view。最终仍由后端 10003 裁决。
 */
export function canManagePetHealth(platformAdmin: boolean, roles: readonly string[] | null | undefined): boolean {
  if (platformAdmin) return true
  return !!roles?.some((role) => role === 'ADMIN' || role === 'DOCTOR' || role === 'CARETAKER')
}

export function hasCustomerSelection(userId: string | null | undefined): boolean {
  return !!userId?.trim()
}

export function canStartPetSubmit(userId: string | null | undefined, busy: boolean): boolean {
  return !busy && hasCustomerSelection(userId)
}

export function petCreateErrorText(code: number, message: string): string {
  if (code === BizCode.FORBIDDEN) return '当前账号无新增宠物权限（customer:update）'
  if (code === BizCode.NOT_FOUND) return '该客户不在本店客户集，请重新选择客户'
  return message || '新增失败，请稍后重试'
}

/** 打开详情抽屉/搜索客户时共用的“只接受最后一次请求”闸。 */
export interface LatestOnlyGate {
  begin(): () => boolean
  invalidate(): void
}

export function createLatestOnly(): LatestOnlyGate {
  let current = 0
  return {
    begin() {
      const mine = ++current
      return () => mine === current
    },
    invalidate() {
      current += 1
    },
  }
}

export function normalizeHealthList(value: readonly string[] | null | undefined): string[] {
  return (value ?? [])
    .filter((item): item is string => typeof item === 'string')
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

export function formatHealthUpdatedAt(value: string | null | undefined): string {
  const raw = value?.trim()
  if (!raw) return '暂无更新时间'
  const normalized = raw.replace('T', ' ')
  return normalized.length > 16 ? normalized.slice(0, 16) : normalized
}

const ROLE_LABEL: Record<string, string> = {
  CUSTOMER: '家长',
  EMPLOYEE: '门店员工',
  DOCTOR: '医生',
  CARETAKER: '护理员',
  STORE_MANAGER: '店长',
  RECEPTIONIST: '前台',
  ADMIN: '平台管理员',
  GROOMER: '美容师',
  DOG_WALKER: '遛狗师',
}

export function healthRoleLabel(role: string | null | undefined): string {
  const raw = role?.trim()
  if (!raw) return '未知角色'
  return ROLE_LABEL[raw] ?? `未知角色（${raw}）`
}

export interface HealthSnapshotView {
  allergies: string[]
  chronicDiseases: string[]
  specialNotes: string
  updatedByRole: string
  updatedAt: string
}

/** 健康明细为空/缺字段时给出可安全渲染的视图，不把 health:null 当成空档案。 */
export function healthSnapshotView(health: PetHealthDetail): HealthSnapshotView {
  return {
    allergies: normalizeHealthList(health.allergies),
    chronicDiseases: normalizeHealthList(health.chronicDiseases),
    specialNotes: health.specialNotes?.trim() || '',
    updatedByRole: healthRoleLabel(health.updatedByRole),
    updatedAt: formatHealthUpdatedAt(health.updatedAt),
  }
}

export function hasHealthSnapshot(health: PetHealthDetail | null | undefined): health is PetHealthDetail {
  return health != null
}

export const PET_FORBIDDEN_CODE = 20101

export function healthErrorText(code: number, message: string): string {
  if (code === PET_FORBIDDEN_CODE) return '该宠物当前不可见或已被删除'
  if (code === BizCode.FORBIDDEN) return '当前账号无宠物查看权限（pet:view）'
  return message || '健康档案加载失败，请稍后重试'
}

/* ---- 健康档案编辑 ---- */

export interface HealthDraft {
  allergiesText: string
  chronicDiseasesText: string
  specialNotes: string
}

export type HealthDraftErrors = Partial<Record<keyof HealthDraft, string>>

export interface HealthDraftValidation {
  errors: HealthDraftErrors
  /** 有校验错误时为 null，调用方不得发请求 */
  body: PetHealthUpdateBody | null
}

/** 一行一项：编辑态与后端 List<String> 的直接映射，不做逗号猜测。 */
export function parseHealthLines(text: string): string[] {
  return text
    .split(/\r?\n/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

export function healthDraftFrom(health: PetHealthDetail | null | undefined): HealthDraft {
  return {
    allergiesText: normalizeHealthList(health?.allergies).join('\n'),
    chronicDiseasesText: normalizeHealthList(health?.chronicDiseases).join('\n'),
    specialNotes: health?.specialNotes ?? '',
  }
}

export function validateHealthDraft(draft: HealthDraft): HealthDraftValidation {
  const allergies = parseHealthLines(draft.allergiesText)
  const chronicDiseases = parseHealthLines(draft.chronicDiseasesText)
  const specialNotes = draft.specialNotes.trim()
  const errors: HealthDraftErrors = {}

  if (allergies.length > 20) {
    errors.allergiesText = '过敏史最多 20 项'
  } else if (allergies.some((item) => item.length > 100)) {
    errors.allergiesText = '单个过敏项不能超过 100 字'
  }
  if (chronicDiseases.length > 20) {
    errors.chronicDiseasesText = '慢性疾病最多 20 项'
  } else if (chronicDiseases.some((item) => item.length > 100)) {
    errors.chronicDiseasesText = '单个慢性疾病项不能超过 100 字'
  }
  if (specialNotes.length > 500) {
    errors.specialNotes = '特殊说明不能超过 500 字'
  }
  if (Object.keys(errors).length > 0) {
    return { errors, body: null }
  }
  return {
    errors,
    body: {
      allergies,
      chronicDiseases,
      specialNotes: specialNotes || null,
    },
  }
}

export function withHealthVersion(
  body: PetHealthUpdateBody,
  expectedUpdatedAt: string | null,
): PetHealthUpdateBody {
  return { ...body, expectedUpdatedAt }
}

export function healthUpdateErrorText(code: number, message: string): string {
  if (code === PET_FORBIDDEN_CODE) return '该宠物当前不可见或已被删除'
  if (code === BizCode.FORBIDDEN) return '当前账号无健康档案编辑权限（pet:health:manage）'
  if (code === BizCode.RATE_LIMITED) return '健康档案已被其他操作更新，请重新读取最新档案后再编辑'
  return message || '健康档案保存失败，请稍后重试'
}

/* ---- 健康档案变更历史 ---- */

export function healthHistoryErrorText(code: number, message: string): string {
  if (code === PET_FORBIDDEN_CODE) return '该宠物当前不可见或已被删除'
  if (code === BizCode.FORBIDDEN) return '当前账号无健康档案查看权限（pet:health:view）'
  return message || '变更历史加载失败，请稍后重试'
}

export type HealthChangeField = 'allergies' | 'chronicDiseases' | 'specialNotes'

const HEALTH_CHANGE_FIELD_LABEL: Record<HealthChangeField, string> = {
  allergies: '过敏史',
  chronicDiseases: '慢性疾病',
  specialNotes: '特殊说明',
}

export function healthChangeFieldLabel(field: string): string {
  return HEALTH_CHANGE_FIELD_LABEL[field as HealthChangeField] ?? field
}

export function healthSnapshotFieldText(
  snapshot: PetHealthSnapshot | null | undefined,
  field: HealthChangeField,
): string {
  if (!snapshot) return '未填写'
  if (field === 'specialNotes') return snapshot.specialNotes?.trim() || '未填写'
  const items = normalizeHealthList(snapshot[field])
  return items.length ? items.join('、') : '未填写'
}

export function healthOperatorLabel(
  change: Pick<PetHealthChange, 'operatorType' | 'operatorId' | 'operatorName' | 'operatorRole'>,
): string {
  const name = change.operatorName?.trim()
  if (name) {
    return change.operatorRole?.trim() ? `${name}（${healthRoleLabel(change.operatorRole)}）` : name
  }
  const id = change.operatorId?.trim() || '未知'
  const role = change.operatorRole?.trim()
  const suffix = role ? `（${healthRoleLabel(role)}）` : ''
  if (change.operatorType === 'CUSTOMER') return `客户 #${id}${suffix}`
  if (change.operatorType === 'ADMIN') return `平台管理员 #${id}`
  return `员工 #${id}${suffix}`
}

export function serviceHistoryErrorText(code: number, message: string): string {
  if (code === PET_FORBIDDEN_CODE) return '该宠物当前不可见或已被删除'
  if (code === BizCode.FORBIDDEN) return '当前账号无服务记录查看权限'
  return message || '服务记录加载失败，请重试'
}

/* ---- 托管日报只读展示 ---- */

const ONLY_ASSIGNED_CARETAKER_CODE = 70001

export function dailyReportErrorText(code: number, message: string): string {
  if (code === BizCode.NOT_FOUND) return '该托管住单不存在或与当前宠物/门店不匹配'
  if (code === BizCode.FORBIDDEN) return '当前账号无托管日报查看权限'
  if (code === ONLY_ASSIGNED_CARETAKER_CODE) return '仅该住单的主责护理员或店长/前台可查看托管日报'
  return message || '托管日报加载失败，请重试'
}

export function reportCaretakerName(records: readonly Pick<CareRecord, 'caretakerName'>[]): string | null {
  return records.find((record) => record.caretakerName?.trim())?.caretakerName?.trim() ?? null
}

export function feedingText(feeding: CareRecord['feeding'] | null | undefined): string {
  if (!feeding) return '未填写'
  const parts = [
    feeding.count != null ? `${feeding.count} 次` : null,
    feeding.amountText?.trim() || null,
  ].filter(Boolean)
  return parts.length ? parts.join(' · ') : '未填写'
}

export function wateringText(watering: CareRecord['watering'] | null | undefined): string {
  return watering?.count != null ? `${watering.count} 次` : '未填写'
}

export function walkText(walk: CareRecord['walk'] | null | undefined): string {
  if (!walk) return '未填写'
  const parts = [
    walk.count != null ? `${walk.count} 次` : null,
    walk.durationMin != null ? `${walk.durationMin} 分钟` : null,
  ].filter(Boolean)
  return parts.length ? parts.join(' · ') : '未填写'
}

/** 后端 health:null 是“无权限/无档案/不可达”的合并语义，不能断言档案不存在。 */
export const HEALTH_UNAVAILABLE_TEXT =
  '当前无法获取健康明细（可能无 pet:health:view 权限、该宠物尚无健康档案，或健康数据暂不可用）。'

export interface ServiceStatusView {
  label: string
  tone: Tone
}

/**
 * 预约状态文案/颜色。未知枚举保留原值 + 中性灰，绝不默认成“预约中”或“已完成”。
 * 枚举以 apt_appointment DDL/现有前端映射为准。
 */
export function serviceStatusView(status: string | null | undefined): ServiceStatusView {
  switch (status) {
    case 'PENDING_PAYMENT':
      return { label: '待支付', tone: 'pink' }
    case 'CONFIRMED':
      return { label: '已确认', tone: 'blue' }
    case 'CANCEL_REQUESTED':
      return { label: '取消审核中', tone: 'purple' }
    case 'WAIT_SERVICE':
      return { label: '待服务', tone: 'blue' }
    case 'SERVING':
      return { label: '服务中', tone: 'orange' }
    case 'COMPLETED':
      return { label: '已完成', tone: 'green' }
    case 'CANCELLED':
      return { label: '已取消', tone: 'gray' }
    default:
      return { label: status?.trim() || '未知状态', tone: 'gray' }
  }
}

/** 记录类型只来自真实履约/托管记录；OTHER 不冒充洗澡/美容。 */
export function serviceRecordTypeLabel(recordType: string | null | undefined): string {
  switch (recordType) {
    case 'BATH':
      return '洗澡 / 美容'
    case 'WALK':
      return '遛狗'
    case 'BOARDING':
      return '托管'
    default:
      return '预约服务'
  }
}

export function formatServiceDateTime(value: string | null | undefined): string {
  const raw = value?.trim()
  if (!raw) return '—'
  const normalized = raw.replace('T', ' ')
  return normalized.length > 16 ? normalized.slice(0, 16) : normalized
}

/** 加载更多时按 appointmentId 去重，避免后端分页边界导致同一预约出现两次。 */
export function mergeServiceRecords(
  existing: readonly PetServiceRecord[],
  incoming: readonly PetServiceRecord[],
): PetServiceRecord[] {
  const seen = new Set(existing.map((row) => row.appointmentId))
  const merged = [...existing]
  for (const row of incoming) {
    if (seen.has(row.appointmentId)) continue
    seen.add(row.appointmentId)
    merged.push(row)
  }
  return merged
}

export function hasMoreServiceRecords(loadedCount: number, total: string | number | null | undefined): boolean {
  const totalCount = Number(total)
  return Number.isFinite(totalCount) && totalCount > loadedCount
}
