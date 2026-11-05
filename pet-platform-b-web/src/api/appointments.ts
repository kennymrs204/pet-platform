import { http } from './client'
import type { PageData } from './dashboard'
import type { StoreServiceItem } from './settings'

/**
 * B 端代客建单接口（新增预约）。**服务端已上线（ADR-059）**：
 *  - 可用时段：`GET /store/store-services/{id}/availability`（C 端同名算法体，闸 `availability:view`，8 角色均持有）；
 *  - 可订服务：`GET /store/store-services/bookable`（只读，闸 `appointment:create`，仅本店 ON 行；
 *    与价目维护的 `GET /store/store-services` 分开，前台不获得 `catalog:store_service:manage`）；
 *  - 创建：`POST /store/customers/{userId}/appointments`（挂客户域，闸 `appointment:create`——
 *    V10 授予店长/ADMIN，前台 V2 既有；下单人=该顾客、source=RECEPTIONIST，员工不替代顾客身份）。
 *  - 门店/归属双闸在服务端重校验：app 层 `requireCustomer`（本店客户集，10004 防枚举）+
 *    预约层 `canManage(customer, pet)`（20101）+"服务属本店"（10004），前端传参不作为信任来源。
 *  - 遛狗（DOG_WALK）`requiredResourceType` 为空：创建链不查/不锁/不占实体资源
 *    （上架写侧已固化该不变量）；营业时间/提前量/员工班次与技能/每日与未付上限照旧。
 *
 * 与 C 端建单链的差异只有两处，其余逐字段同形：可用时段与创建端点的前缀（/store vs /customer）。
 *
 * 建单不代付：请求体无支付字段，落库即 PENDING_PAYMENT，顾客在 C 端「订单」页完成支付；
 * 线下收款走订单侧登记/复核（`POST /store/orders/{id}/offline-payment`，V18）。数据落同一张 apt_appointment 表、
 * user_id=顾客 → C 端「我的预约」天然可见，本功能不需要 C 端任何改动。
 */

export interface AvailabilitySlot {
  start: string
  end: string
}

export interface KennelOption {
  id: string
  name: string
  type: string
}

export interface EmployeeOption {
  id: string
  name: string
}

/** 与后端 AvailabilityView 同形（Long 已字符串化）。 */
export interface AvailabilityView {
  storeServiceId: string
  fulfillmentType: 'INSTANT' | 'MULTI_DAY' | string
  date: string
  allowUserPickEmployee: boolean
  slots: AvailabilitySlot[]
  kennels: KennelOption[]
  employeeOptions: EmployeeOption[]
}

/**
 * 创建请求体（与 C 端 AppointmentCreateRequest 逐字段一致）。
 * INSTANT 传 `serviceDate`+`startTime`；MULTI_DAY 传入住/离店日期；两类字段不得混传。
 * requestId = 幂等键（Redis SETNX，TTL 5min），同一组选择重试复用、改选择即换新值。
 */
export interface AppointmentCreateRequest {
  requestId: string
  petId: string
  storeServiceId: string
  employeeId?: string | null
  serviceDate?: string | null
  startTime?: string | null
  boardingCheckinDate?: string | null
  boardingCheckoutDate?: string | null
  remark?: string | null
}

export interface AppointmentCreatedView {
  appointmentId: string
  appointmentNo: string
  status: string
  expireAt: string | null
  serviceNameSnapshot: string
  priceSnapshot: string | number
  durationSnapshot: number | null
  orderId: string
  orderNo: string
  amount: string | number
  paymentParams: Record<string, string> | null
}

export const appointmentsApi = {
  /**
   * 可订服务下拉：本店 ON 上架行（只读端点，闸 `appointment:create`——前台/店长/ADMIN）。
   * 刻意不用 `/store/store-services` 列表：那条是价目维护读口（`catalog:store_service:manage`），
   * 前台为建单不应获得该管理面。店范围只认 X-Store-Id，服务端强制 status=ON 且最多 50 条。
   */
  bookableServices: () =>
    http.get<PageData<StoreServiceItem>>('/store/store-services/bookable', { auth: true }),

  /** 可约时段：服务/日期/宠物任一变化重拉；30min 对齐、营业窗、资源占用均由服务端计算。 */
  availability: (storeServiceId: string, date: string, petId?: string) =>
    http.get<AvailabilityView>(
      `/store/store-services/${storeServiceId}/availability?date=${date}${petId ? `&petId=${petId}` : ''}`,
      { auth: true },
    ),

  /** 代客建单：`customerUserId` 从客户 360 带入，单据落在该顾客 userId 名下。 */
  create: (customerUserId: string, body: AppointmentCreateRequest) =>
    http.post<AppointmentCreatedView>(`/store/customers/${customerUserId}/appointments`, body, { auth: true }),
}

/** 服务分类展示名（与价目表页同口径）。 */
export const SERVICE_CATEGORY_LABEL: Record<string, string> = {
  GROOMING: '洗护',
  MEDICAL: '医疗',
  BOARDING: '寄养',
  TRAINING: '训练',
  WALK: '遛狗',
  FEEDING: '喂养',
  CARE: '照顾',
}

/**
 * 创建预约的错误码人话表（B 端文案：主语是"这位顾客/这只宠物"）。
 * 码段与后端第八阶段 §2-2 一致；B 端 types.ts 的 BizCode 未收录的用字面量+
 * 注释（C 端同名码：PET_NOT_MANAGEABLE=20101 / PET_NOT_BOOKABLE=20102 /
 * STORE_CLOSED=30001 / SERVICE_OFF_SHELF=31001）。
 */
export const CREATE_ERROR_TEXT: Record<number, string> = {
  10004: '找不到该客户、服务或宠物（或他们不归本店），请刷新后重试',
  20101: '这只宠物不属于该客户，请重新选择',
  20102: '宠物当前状态不可预约（需为正常状态），可先编辑档案调整',
  30001: '门店当前未营业，换个时间试试',
  31001: '该服务已下架，请重新选择',
  40001: '所选员工当日无排班，试试随机安排',
  40002: '所选员工该时段已有安排，换一个时段试试',
  40003: '所选员工不具备该服务所需技能',
  40004: '该时段没有可用资源（洗浴台/诊室/笼位），换个时段吧',
  40005: '笼位容量不足，换个日期或时段试试',
  50001: '该时段刚刚被约满，请重新选择',
  50002: '超出可预约时间窗口（太早，或超过 30 天）',
  50003: '该预约状态已变化，无法操作，请刷新后重试',
  10005: '提交太快或已重复提交，请先刷新确认这一单是否已受理',
  50005: '该客户未支付订单过多，请先完成或取消已有订单',
  50006: '该客户今日预约创建次数已达上限',
}

/** 网络层兜底：非 BizError 的失败提示（与客户页口径一致，不吞异常）。 */
export function createErrText(e: unknown): string {
  const code = (e as { code?: number } | null)?.code
  if (typeof code === 'number') return CREATE_ERROR_TEXT[code] ?? (e as Error).message
  return e instanceof Error ? e.message : '提交失败，请重试'
}
