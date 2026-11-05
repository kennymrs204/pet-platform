import { http } from './client'
import type { PageData } from './dashboard'

/**
 * B 端宠物管理接口（后端契约：ADR-014 / ADR-058，StorePetController + StorePetWriteController）。
 * 约定：Long 一律字符串（含各计数）；storeId 不在前端——X-Store-Id 由统一 client 注入；
 * species/status 枚举受后端 @Pattern 校验；pageSize 后端钳制 ≤100。
 */

export type PetSpecies = 'DOG' | 'CAT' | 'OTHER'
export type PetGender = 'MALE' | 'FEMALE' | 'UNKNOWN' | null
export type PetStatus = 'NORMAL' | 'DECEASED' | 'ARCHIVED'

export interface PetHealthSummary {
  allergyCount: number
  chronicCount: number
  hasSpecialNotes: boolean
  vaccineSummary: string | null
}

export interface PetLastService {
  /** SERVICE=履约记录（真实完成），APPOINTMENT=预约快照兜底 */
  source: 'SERVICE' | 'APPOINTMENT'
  appointmentId: string | null
  /** SERVICE 时为 BATH/WALK；APPOINTMENT 时为服务名快照 */
  label: string | null
  finishedAt: string | null
  serviceDate: string | null
  /** 仅 APPOINTMENT 兜底时有值 */
  status: string | null
}

export interface PetBoardingBrief {
  stayId: string
  /** CHECKED_IN/DAILY_CARE */
  status: string
  checkinDate: string
  checkoutDate: string
}

export interface PetItem {
  id: string
  name: string
  species: PetSpecies
  breed: string | null
  gender: PetGender
  birthday: string | null
  birthdayApprox: boolean | null
  weightKg: number | string | null
  sizeCategory: string | null
  /** 裸对象 key（ADR-029），不可直接当图片地址用 */
  avatar: string | null
  /** presigned GET：出图只用这个字段，签不出为 null → 渲染占位 */
  avatarUrl: string | null
  status: PetStatus
  health: PetHealthSummary | null
  lastService: PetLastService | null
  boarding: PetBoardingBrief | null
}

export interface PetOverview {
  totalPets: string
  dogCount: string
  catCount: string
  inBoardingCount: string
}

export interface PetListParams {
  species?: PetSpecies
  status?: PetStatus
  keyword?: string
  pageNum?: number
  pageSize?: number
}

/** 单点出参的 pet 主体（后端 PetVO，比列表项多绝育/备注/疫苗摘要/建档时间）。 */
export interface PetProfile {
  id: string
  name: string
  species: PetSpecies
  breed: string | null
  gender: PetGender
  isNeutered: boolean | null
  birthday: string | null
  birthdayApprox: boolean | null
  weightKg: number | string | null
  sizeCategory: string | null
  /** 裸对象 key（ADR-029），不可直接当图片地址用 */
  avatar: string | null
  avatarUrl: string | null
  remark: string | null
  vaccineSummary: string | null
  status: PetStatus
  createdAt: string | null
}

/**
 * 健康明细（PetHealthVO）。**三重语义的 null**：无 `pet:health:view` 权限、该宠物尚无健康档案、
 * 或存储层不可达——前端统一按"未提供"展示并说明可能原因，不猜是哪一种。
 */
export interface PetHealthDetail {
  petId: string
  allergies: string[] | null
  chronicDiseases: string[] | null
  specialNotes: string | null
  updatedByRole: string | null
  /** 毫秒精度；同时作为下一次健康编辑的乐观并发令牌 */
  updatedAt: string | null
}

/** 健康三字段整体覆盖请求；不包含 updatedByRole/操作人/门店。 */
export interface PetHealthUpdateBody {
  allergies: string[]
  chronicDiseases: string[]
  specialNotes: string | null
  /**
   * 读取档案时的 updatedAt（毫秒精度版本令牌）。B 端编辑必须携带；
   * 服务端在行锁内比较，不一致返回 10005，客户端必须重读后重新编辑。
   */
  expectedUpdatedAt?: string | null
}

/** 变更日志中的健康三字段快照。 */
export interface PetHealthSnapshot {
  allergies: string[]
  chronicDiseases: string[]
  specialNotes: string | null
}

/** 健康档案一次真实变更（只来自 append-only 日志，不由当前值推导）。 */
export interface PetHealthChange {
  id: string
  changedAt: string | null
  operatorType: string | null
  operatorId: string | null
  operatorName: string | null
  operatorRole: string | null
  changedFields: string[]
  before: PetHealthSnapshot
  after: PetHealthSnapshot
}

/**
 * `GET /store/pets/{id}` 出参（StorePetDetailVO）。
 * ⚠ 可见范围 = 本店预约关联宠物 ∪ 本店在店托管宠物（appointment:view:store 持有者），
 * 范围外一律 **20101**（`PET_FORBIDDEN`）；客户 360 里的宠物来自归属关系（usr_user_pet），
 * 因此"客户在本店没带来过的那只"点开必然 20101——这是范围口径，不是故障。
 */
export interface StorePetDetail {
  pet: PetProfile
  healthSummary: PetHealthSummary | null
  lastService: PetLastService | null
  boarding: PetBoardingBrief | null
  health: PetHealthDetail | null
}

/** 洗澡/美容履约详情（fulfillment 的 ServiceRecordBrief）。 */
export interface PetBathRecord {
  appointmentId: string
  employeeId: string | null
  startedAt: string
  finishedAt: string
  serviceItems: string[]
  remark: string | null
  photoCount: number
  byManager: boolean
}

/** 遛狗履约详情（fulfillment 的 WalkRecordBrief）。 */
export interface PetWalkRecord {
  appointmentId: string
  employeeId: string | null
  startedAt: string
  finishedAt: string
  durationMin: number
  remark: string | null
  byManager: boolean
}

/** 托管住单摘要（boarding 的 BoardingStayView）。 */
export interface PetBoardingStay {
  id: string
  appointmentId: string
  petId: string
  checkinDate: string
  checkoutDate: string
  actualCheckinAt: string | null
  status: string
  caretakerId: string | null
}

/**
 * B 端宠物当前门店服务历史一行。预约是主脊柱；详情对象按 appointmentId 合并，
 * 没有履约记录时字段为 null，但行本身仍保留。
 */
export interface PetServiceRecord {
  appointmentId: string
  appointmentNo: string
  petId: string
  serviceDate: string
  startTime: string | null
  endTime: string | null
  serviceName: string
  status: string
  priceSnapshot: string | null
  employeeId: string | null
  employeeName: string | null
  /** BATH/WALK/BOARDING/OTHER；由真实履约/托管记录判定，不按服务名猜。 */
  recordType: string
  finishedAt: string | null
  bathRecord: PetBathRecord | null
  walkRecord: PetWalkRecord | null
  boardingStay: PetBoardingStay | null
  boardingCaretakerName: string | null
}

export interface PetServiceHistoryParams {
  pageNum?: number
  pageSize?: number
}

/** B 端受限基础资料更新请求；刻意不含 avatar/status/owner/origin/sizeCategory。 */
export interface PetStoreUpdateBody {
  name: string
  species: PetSpecies
  breed: string | null
  gender: 'MALE' | 'FEMALE' | 'UNKNOWN' | null
  isNeutered: boolean | null
  birthday: string | null
  birthdayApprox: boolean | null
  weightKg: number | null
  remark: string | null
}

export const petsApi = {
  overview: () => http.get<PetOverview>('/store/pets/overview', { auth: true }),
  list: (p: PetListParams = {}) => {
    const q = new URLSearchParams()
    if (p.species) q.set('species', p.species)
    if (p.status) q.set('status', p.status)
    if (p.keyword) q.set('keyword', p.keyword)
    q.set('pageNum', String(p.pageNum ?? 1))
    q.set('pageSize', String(p.pageSize ?? 20))
    return http.get<PageData<PetItem>>(`/store/pets?${q.toString()}`, { auth: true })
  },
  detail: (id: string, opts: { signal?: AbortSignal } = {}) =>
    http.get<StorePetDetail>(`/store/pets/${id}`, { auth: true, signal: opts.signal }),
  update: (id: string, body: PetStoreUpdateBody, opts: { signal?: AbortSignal } = {}) =>
    http.put<PetProfile>(`/store/pets/${id}`, body, { auth: true, signal: opts.signal }),
  /** B 端删除；后端会阻断未完成预约/托管，历史数据不级联删除。 */
  remove: (id: string, opts: { signal?: AbortSignal } = {}) =>
    http.delete<void>(`/store/pets/${id}`, { auth: true, signal: opts.signal }),
  /** 健康档案整体覆盖；操作人/门店由服务端从登录主体与 X-Store-Id 产生。 */
  updateHealth: (id: string, body: PetHealthUpdateBody, opts: { signal?: AbortSignal } = {}) =>
    http.put<PetHealthDetail>(`/store/pets/${id}/health`, body, { auth: true, signal: opts.signal }),
  /** 最近 50 条真实变更；当前门店/任务范围不可见时 20101。 */
  healthHistory: (id: string, opts: { signal?: AbortSignal } = {}) =>
    http.get<PetHealthChange[]>(`/store/pets/${id}/health-history`, { auth: true, signal: opts.signal }),
  serviceHistory: (
    id: string,
    p: PetServiceHistoryParams = {},
    opts: { signal?: AbortSignal } = {},
  ) => {
    const q = new URLSearchParams()
    q.set('pageNum', String(p.pageNum ?? 1))
    q.set('pageSize', String(p.pageSize ?? 20))
    return http.get<PageData<PetServiceRecord>>(`/store/pets/${id}/service-records?${q.toString()}`, {
      auth: true,
      signal: opts.signal,
    })
  },
}

/** 生日 → 年龄文案；birthdayApprox 加「约」；无生日返回缺省。 */
export function petAgeText(birthday: string | null, approx: boolean | null): string {
  if (!birthday) return '年龄未登记'
  const b = new Date(`${birthday}T00:00:00`)
  if (Number.isNaN(b.getTime())) return '年龄未登记'
  const now = new Date()
  let months = (now.getFullYear() - b.getFullYear()) * 12 + (now.getMonth() - b.getMonth())
  if (now.getDate() < b.getDate()) months -= 1
  if (months < 0) months = 0
  const y = Math.floor(months / 12)
  const m = months % 12
  const age = y > 0 ? (m > 0 ? `${y}岁${m}个月` : `${y}岁`) : `${m}个月`
  return approx ? `约${age}` : age
}

export function petWeightText(weightKg: number | string | null): string {
  if (weightKg === null || weightKg === '') return '体重未登记'
  const n = Number(weightKg)
  return Number.isFinite(n) ? `${n}kg` : '体重未登记'
}

export const PET_STATUS_LABEL: Record<PetStatus, string> = {
  NORMAL: '正常',
  DECEASED: '已故',
  ARCHIVED: '已归档',
}

export const SPECIES_LABEL: Record<PetSpecies, string> = {
  DOG: '犬',
  CAT: '猫',
  OTHER: '其他',
}
