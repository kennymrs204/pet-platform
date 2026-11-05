import { http } from './client'
import { BizCode } from './types'
import type { PageResult } from './catalog'

/**
 * C 端预约与可约查询（ADR-016 §5/§7，后端 CustomerAppointmentController + CustomerAvailabilityController）。
 * 两处与 ADR 文字不一致的地方以代码为准，已按实际实现接线：
 *  - availability 的查询参数是 `date` + `petId?`（ADR-016 §5 表格写的是 employeeId）；
 *  - 创建请求体的时段字段是 `serviceDate` + `startTime`（INSTANT）或
 *    `boardingCheckinDate`/`boardingCheckoutDate`（MULTI_DAY），二者互斥，由服务端按服务的
 *    fulfillmentType 判定。
 * requestId 为幂等键（Redis SETNX，TTL 5min），重复提交返回 10005。
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

export interface AvailabilityView {
  storeServiceId: string
  fulfillmentType: 'INSTANT' | 'MULTI_DAY' | string
  date: string
  allowUserPickEmployee: boolean
  slots: AvailabilitySlot[]
  kennels: KennelOption[]
  employeeOptions: EmployeeOption[]
}

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

export interface AppointmentSelfView {
  id: string
  appointmentNo: string
  petId: string
  storeId: string
  storeServiceId: string
  employeeId: string | null
  serviceDate: string | null
  startTime: string | null
  endTime: string | null
  boardingCheckinDate: string | null
  boardingCheckoutDate: string | null
  status: string
  serviceNameSnapshot: string
  priceSnapshot: string | number
  durationSnapshot: number | null
  remark: string | null
  expireAt: string | null
  createdAt: string
}

export const appointmentApi = {
  availability: (storeServiceId: string, date: string, petId?: string) =>
    http.get<AvailabilityView>(
      `/customer/store-services/${storeServiceId}/availability?date=${date}${petId ? `&petId=${petId}` : ''}`,
      { auth: true },
    ),
  create: (body: AppointmentCreateRequest) =>
    http.post<AppointmentCreatedView>('/customer/appointments', body, { auth: true }),
  list: (status?: string, pageNum = 1, pageSize = 20) =>
    http.get<PageResult<AppointmentSelfView>>(
      `/customer/appointments?status=${encodeURIComponent(status ?? '')}&pageNum=${pageNum}&pageSize=${pageSize}`,
      { auth: true },
    ),
  detail: (id: string) => http.get<AppointmentSelfView>(`/customer/appointments/${id}`, { auth: true }),
  /**
   * 我这条预约的遛狗服务报告（只读）：完成时间、实际服务时长、现签照片与 GPS 轨迹摘要。
   * 归属由服务端按 selfDetail 判定（别人的预约=10004）；
   * 非遛狗预约/尚未完成时 `hasRecord=false`、两个数组为空——这是正常空态，不是错误。
   * `photos` 是对象 key，`photoUrls` 是现签 GET（600s），两者同序（签不出的会被过滤掉）。
   */
  walkRecord: (id: string) =>
    http.get<WalkRecordPhotos>(`/customer/appointments/${id}/walk-record`, { auth: true }),
  /** ADR-023：取消预约。未付直接关单（refundState=NONE）；已付进退款申请（APPLYING + 金额）。 */
  cancel: (id: string, body: { requestId: string; reason?: string | null }) =>
    http.post<AppointmentCancelView>(`/customer/appointments/${id}/cancel`, body, { auth: true }),
  /**
   * ADR-026：改期。**仅 PENDING_PAYMENT 可改**，返回变更后的预约视图（后端同时重置 expire_at）。
   * 白名单：INSTANT 传 `serviceDate`+`startTime`（成对）+ 可选 `employeeId`；
   * MULTI_DAY 传入住/离店（成对），`serviceDate` 由后端联动=入住日。
   * ⚠️ 后端 `updateModifyInstant` 无条件写 `employee_id`，不想动员工就必须回传原值。
   */
  modify: (id: string, body: ModifyAppointmentRequest) =>
    http.put<AppointmentSelfView>(`/customer/appointments/${id}/modify`, body, { auth: true }),
}

/** 本次遛狗的 GPS 轨迹摘要（服务端持久化值；距离/时长/点数不由前端重算）。 */
export interface AppointmentWalkTrack {
  id: string
  appointmentId: string
  trackId: string
  startedAt: string
  endedAt: string
  /** 实际记录时长（秒）= 末点 − 首点 − 暂停 */
  durationSec: number
  pausedSec: number
  distanceM: number
  pointCount: number
  /** 抽稀后的 [lat,lng] 序列，仅用于画示意路线 */
  polyline: [number, number][]
}

/** 我这条预约的遛狗服务报告（C 端只读）。非遛狗/未完成时 hasRecord=false 且两个数组为空。 */
export interface WalkRecordPhotos {
  appointmentId: string
  hasRecord: boolean
  finishedAt: string | null
  durationMin: number | null
  /** 对象 key */
  photos: string[]
  /** 与 photos 同序的 presigned GET（600s） */
  photoUrls: string[]
  /** 本次轨迹；null=未采集（与"0 米"可区分） */
  track: AppointmentWalkTrack | null
}

export interface ModifyAppointmentRequest {
  requestId: string
  serviceDate?: string | null
  startTime?: string | null
  employeeId?: string | number | null
  boardingCheckinDate?: string | null
  boardingCheckoutDate?: string | null
}

/**
 * 组装改期请求体（ADR-026 §1 白名单）。抽成纯函数是因为这里藏着一条会静默改数据的坑：
 * 后端 `updateModifyInstant` 无条件写 `employee_id = #{employeeId}`，**漏传就等于把已分配的员工清空**。
 * 所以「保留原员工」必须是显式回传原值，只有用户主动点「改为随机安排」才传 null。
 */
export function buildModifyRequest(
  appt: Pick<AppointmentSelfView, 'boardingCheckinDate' | 'employeeId'>,
  sel: { requestId: string; date: string; startTime: string | null; checkin: string; checkout: string; keepEmployee: boolean },
): ModifyAppointmentRequest {
  if (appt.boardingCheckinDate) {
    return {
      requestId: sel.requestId,
      serviceDate: null,
      startTime: null,
      employeeId: null,
      boardingCheckinDate: sel.checkin,
      boardingCheckoutDate: sel.checkout,
    }
  }
  return {
    requestId: sel.requestId,
    serviceDate: sel.date,
    startTime: sel.startTime,
    employeeId: sel.keepEmployee ? appt.employeeId : null,
    boardingCheckinDate: null,
    boardingCheckoutDate: null,
  }
}

/**
 * 取消结果（ADR-023 §1/§2/§4）。退款额由后端按**当前上架**的
 * cancel_free_hours / cancel_penalty_rate 实时计算（apt 无政策快照列），
 * 所以前端不预估退款金额，只在提交后如实展示返回值。
 */
export interface AppointmentCancelView {
  appointmentId: string
  appointmentNo: string
  status: string
  /** NONE=未支付关单；APPLYING=已支付进入退款申请（渠道执行与审批属退款专段，本期未到账） */
  refundState: 'NONE' | 'APPLYING' | string
  refundAmount: string | number | null
}

/** 取消链路的错误码人话表（ADR-023 §1/§2/§4，全部出自 §2-2 既有段）。 */
export const CANCEL_ERROR_TEXT: Record<number, string> = {
  10004: '找不到这条预约，或它不属于当前账号',
  10005: '提交太快或已重复提交，请先刷新确认这条预约的状态',
  50003: '这条预约已有取消申请在审核中，请等门店处理；或它已完成/已取消',
  50004: '服务已开始或正在排队，无法取消，请联系门店处理',
  60003: '订单状态刚刚发生变化（可能已支付或已取消），请刷新后重试',
  60004: '退款金额超出该订单实付，已拒绝，请联系门店处理',
}

/** 创建预约的错误码人话表（ADR-016 §5 列出的全部取值，均出自第八阶段 §2-2 既有码段）。 */
export const CREATE_ERROR_TEXT: Record<number, string> = {
  10004: '找不到这条预约，或它不属于当前账号',
  50003: '该预约已支付或状态已变化，无法改期（已支付的单请走「取消并申请退款」后重新预约）',
  [BizCode.RATE_LIMITED]: '提交太快或已重复提交，请先去预约列表确认这一单是否已受理',
  [BizCode.PET_NOT_MANAGEABLE]: '这只宠物不属于当前账号，请重新选择',
  [BizCode.PET_NOT_BOOKABLE]: '宠物当前状态不可预约（需为正常状态）',
  [BizCode.STORE_CLOSED]: '门店当前未营业，换个时间或门店试试',
  [BizCode.SERVICE_OFF_SHELF]: '该服务已下架，请重新选择',
  40001: '所选员工当日无排班，试试随机安排',
  40002: '所选员工该时段已有安排，换一个时段试试',
  40003: '所选员工不具备该服务所需技能',
  40004: '该时段没有可用资源（洗浴台/诊室/笼位），换个时段吧',
  40005: '笼位容量不足，换个日期或时段试试',
  50001: '该时段刚刚被约满，请重新选择',
  50002: '超出可预约时间窗口（太近或太远）',
  50005: '未支付订单过多，请先完成或取消已有订单',
  50006: '今日预约创建次数已达上限',
}
