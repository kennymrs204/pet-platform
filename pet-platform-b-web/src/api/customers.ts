import { http } from './client'
import type { PageData } from './dashboard'
import type { PetProfile } from './pets'

/**
 * B 端客户管理接口（契约 = ADR-017 §5，后端已实现并验证在线）。
 * **直连真实数据，不走演示兜底**：`/store/customers*` 已上线（app 层 StoreCustomerController），
 * 演示数据集随之删除——用假数据掩盖权限或部署问题，代价远高于"暂时打不开"。
 *
 * 口径回顾（ADR-017）：
 * - 本店客户 = 在本店有过预约的顾客，且已剔除员工账号；
 * - 手机号后端脱敏（前 3 后 4），无明文查询入口；
 * - `totalPaidAmount` 受 `order:view:store` 门控，无权限时为 null（不是 0）；
 * - 跨店或不存在的 userId 统一 10004，不泄露存在性；
 * - 金额与所有计数是 Long/long → 全局 Jackson 字符串化，前端一律按 string 处理。
 */

export type CustomerTab = 'ALL' | 'ACTIVE' | 'DORMANT' | 'IN_BOARDING'
export type CustomerStatus = 'NORMAL' | 'DISABLED'

export interface CustomerRow {
  userId: string
  nickname: string
  /**
   * 后端 presigned GET 地址（ADR-029，600s 时效，刷新页面即重签）；
   * 桶主不是该客户本人、或对象存储未开启时为 null → 前端回落首字母占位。
   * **此字段不再是裸对象存储 key**（曾给 key，img src 直接破图）。
   */
  avatarUrl: string | null
  phoneMasked: string
  status: CustomerStatus
  firstVisitDate: string | null
  lastVisitDate: string | null
  visitCount: string
  /** null = 当前账号无 order:view:store，字段不披露 */
  totalPaidAmount: string | null
  inStoreStayCount: string
  hasValidAppointment: boolean
}

export interface CustomerProfile {
  registeredAt: string | null
  /**
   * 顾客自选偏好门店，非归属关系。后端经 `StoreQueryApi.storeNames` 换名后给出真实店名；
   * 客户没选、或所选门店已删/不可见 → null（两种情形前端同样显示「未选择」，后端不区分）。
   */
  preferredStoreName: string | null
  marketingOptIn: boolean
  /** 默认联系电话（ADR-027；手机号或空）——门店侧编辑回显/展示用 */
  defaultContactPhone: string | null
  /** 门店侧客户备注（ADR-058；C 端不展示） */
  remark: string | null
}

export interface CustomerPetRef {
  petId: string
  name: string
  species: string
  breed: string | null
  /** presigned GET（同 CustomerRow.avatarUrl）；无 pet:view 时后端一律不披露 → null */
  avatarUrl: string | null
}

export interface CustomerVisit {
  appointmentId: string
  serviceDate: string
  serviceName: string
  /** PENDING_PAYMENT/CONFIRMED/WAIT_SERVICE/SERVING/COMPLETED/CANCELLED */
  status: string
  amount: string | null
  employeeName: string | null
}

export interface CustomerDetail extends CustomerRow {
  profile: CustomerProfile
  pets: CustomerPetRef[]
  visits: CustomerVisit[]
}

export interface CustomerStats {
  totalCustomers: string
  newLast30Days: string
  inBoardingCustomers: string
}

/** 客户订单史行（打通轮 P4）：快照金额；顾客信息不重复携带（外层详情已有） */
export interface CustomerOrderRow {
  id: string
  orderNo: string
  appointmentId: string | null
  /** WAIT_PAY/PAID/WAIT_SERVICE/SERVING/COMPLETED/CANCELLED/REFUNDING/REFUNDED */
  status: string
  amount: string
  paidAmount: string
  /** ONLINE/OFFLINE；未支付为 null */
  paymentMethod: string | null
  paidAt: string | null
  createdAt: string
  serviceName: string | null
}

export interface CustomerListParams {
  tab?: CustomerTab
  keyword?: string
  pageNum?: number
  pageSize?: number
}

export const customersApi = {
  list: (p: CustomerListParams = {}) => {
    const q = new URLSearchParams()
    if (p.tab && p.tab !== 'ALL') q.set('tab', p.tab)
    if (p.keyword) q.set('keyword', p.keyword)
    q.set('pageNum', String(p.pageNum ?? 1))
    q.set('pageSize', String(p.pageSize ?? 20))
    return http.get<PageData<CustomerRow>>(`/store/customers?${q.toString()}`, { auth: true })
  },
  stats: () => http.get<CustomerStats>('/store/customers/stats', { auth: true }),
  detail: (userId: string) => http.get<CustomerDetail>(`/store/customers/${userId}`, { auth: true }),
  /**
   * 门店代客建档（ADR-057）：只建平台里没有的新号码——已存在（注册用户或他店/本店已建档）一律 20001。
   * 建档成功返回可直接插入列表的"新行"（零到店、无头像、无消费），响应体与 CustomerRow 同形。
   */
  create: (body: { phone: string; nickname: string }) => http.post<CustomerRow>('/store/customers', body, { auth: true }),
  /**
   * 门店侧编辑客户资料（ADR-058）：三列定点更新——姓名 / 默认联系电话 / 客户备注。
   * 返回更新后的客户 360，前端可直接用它刷新；手机号/头像/偏好/营销不在射程内（见 ADR-058）。
   */
  update: (userId: string, body: { nickname: string; defaultContactPhone: string | null; remark: string | null }) =>
    http.put<CustomerDetail>(`/store/customers/${userId}`, body, { auth: true }),
  /**
   * 门店代客建宠物（ADR-058）：校验与落库走 C 端同一条路径（尺寸按体重算、owner=该客户）；
   * 头像需顾客在 C 端补传（上传通道只签"自己的桶"）。返回新建的宠物档案。
   */
  createPet: (userId: string, body: {
    name: string
    species: string
    breed?: string | null
    gender?: string | null
    birthday?: string | null
    weightKg?: number | null
    remark?: string | null
  }) => http.post<PetProfile>(`/store/customers/${userId}/pets`, body, { auth: true }),
  /**
   * 到店史分页（与 detail.visits 同源同形）。服务端把 limit 夹在 [1,100]、offset 夹 ≥0
   * （AppointmentQueryService），排序 service_date DESC, id DESC 前缀稳定
   * ⇒ 按 offset 翻页时每次只查当页，页间不重不漏。
   */
  appointments: (userId: string, limit: number, offset = 0) =>
    http.get<CustomerVisit[]>(`/store/customers/${userId}/appointments?limit=${limit}&offset=${offset}`, { auth: true }),
  /**
   * 客户订单史分页（打通轮 P4）：本店该客户的订单，排序 created_at DESC→id DESC，
   * 真实 total 分页（与订单管理页同模式）。金额为创建时快照；权限码 order:view:store。
   */
  orders: (userId: string, pageNum = 1, pageSize = 10) =>
    http.get<PageData<CustomerOrderRow>>(`/store/customers/${userId}/orders?pageNum=${pageNum}&pageSize=${pageSize}`, { auth: true }),
}

export const CUSTOMER_TAB_LABEL: Record<CustomerTab, string> = {
  ALL: '全部客户',
  ACTIVE: '活跃客户',
  DORMANT: '沉睡客户',
  IN_BOARDING: '在店托管',
}

export const CUSTOMER_STATUS_LABEL: Record<CustomerStatus, string> = {
  NORMAL: '正常',
  DISABLED: '已停用',
}

export const VISIT_STATUS_LABEL: Record<string, string> = {
  PENDING_PAYMENT: '待支付',
  CONFIRMED: '已确认',
  /** ADR-043：客户已提交取消申请、等门店审批退款——还没取消 */
  CANCEL_REQUESTED: '取消审核中',
  WAIT_SERVICE: '待服务',
  SERVING: '服务中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
}

/** 距今天数（本地历法，禁 toISOString 以免时区偏移）；无值返回 null。 */
export function daysSince(date: string | null): number | null {
  if (!date) return null
  const d = new Date(`${date.slice(0, 10)}T00:00:00`)
  if (Number.isNaN(d.getTime())) return null
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return Math.round((today.getTime() - d.getTime()) / 86400000)
}

/** 昵称首字（中日韩取第 1 字，拉丁取大写首字母）——无头像时的占位。 */
export function customerInitial(nickname: string): string {
  const ch = (nickname ?? '').trim().charAt(0) || '客'
  return /[a-zA-Z]/.test(ch) ? ch.toUpperCase() : ch
}
