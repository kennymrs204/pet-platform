import { http } from './client'

/**
 * B 端首页真实数据接口（后端契约：ADR-013）。
 * 约定：
 * - 不传 date → 后端按业务时区 Asia/Shanghai 判定「今日」，前端不自算日期；
 * - Long 一律字符串（含金额=分、total、各 id）；deltaPercent 为 number|null（昨日为 0 时 null）；
 * - X-Store-Id 由统一 client 注入当前门店上下文，未就绪时请求快速失败（BizCode.NO_STORE_CTX）。
 */

export interface RevenueView {
  todayAmount: string
  yesterdayAmount: string
  deltaAmount: string
  deltaPercent: number | null
}

export interface OverviewView {
  date: string
  inStorePetCount: string
  todayOrderCount: string
  onDutyStaffCount: string
  revenue: RevenueView
}

export interface PetRef {
  id: string
  name: string | null
  species: string | null
  breed: string | null
  gender?: string | null
  /** 对象存储裸 key（ADR-029），不是可访问 URL → 进 `<img src>` 前必须过 `lib/mediaSrc` */
  avatar?: string | null
  /** presigned GET：出图只用这个字段，签不出为 null → 渲染占位 */
  avatarUrl?: string | null
}

export interface EmployeeRef {
  id: string
  name: string | null
}

/**
 * 住单行内的客户（宠物主登记人）。后端口径：
 * - 整个字段为 null = 当前账号无 customer:view:store（护理员/医生等任务角色看不到客户身份）；
 * - nickname 为 null = 该宠物没有 OWNER 归属行，如实显示「主人未登记」，不伪造；
 * - phoneMasked 已由服务端脱敏（前 3 后 4），前端不再加工。
 */
export interface BoardingCustomer {
  userId: string | null
  nickname: string | null
  phoneMasked: string | null
}

export interface AppointmentItem {
  id: string
  appointmentNo: string
  serviceDate: string
  startTime: string | null
  endTime: string | null
  /** PENDING_PAYMENT/CONFIRMED/WAIT_SERVICE/SERVING/COMPLETED/CANCELLED */
  status: string
  serviceName: string
  priceSnapshot: string
  source: string
  pet: PetRef | null
  employee: EmployeeRef | null
}

export interface PageData<T> {
  total: string
  pageNum: number
  pageSize: number
  list: T[]
}

export interface BoardingStayItem {
  id: string
  appointmentId: string
  /** CHECKED_IN/DAILY_CARE（缺省即在店集合） */
  status: string
  /** 关联预约状态（后端批量回填）；在住单应为 SERVING，漂移时离店确认面板前置禁用。旧镜像无此字段=undefined */
  appointmentStatus?: string | null
  checkinDate: string
  checkoutDate: string
  actualCheckinAt: string | null
  pet: PetRef | null
  caretaker: EmployeeRef | null
  /** 客户（宠物主登记人）；无 customer:view:store 时后端不下发 → null/undefined */
  customer?: BoardingCustomer | null
}

export interface ScheduleItem {
  id: string
  employeeId: string
  employeeName: string | null
  roleCodes: string[]
  workDate: string
  startTime: string
  endTime: string
  /** WORKING/LEAVE */
  status: string
  leaveStatus: string
}

export const dashboardApi = {
  overview: () => http.get<OverviewView>('/store/stats/overview', { auth: true }),
  todayAppointments: (pageSize = 5) =>
    http.get<PageData<AppointmentItem>>(`/store/appointments?pageNum=1&pageSize=${pageSize}`, { auth: true }),
  inStorePets: () => http.get<BoardingStayItem[]>('/store/boarding-stays', { auth: true }),
  todaySchedules: () => http.get<ScheduleItem[]>('/store/schedules', { auth: true }),
}

/** 分 → 「¥1,234.56」；BigInt 字符串运算，无浮点误差（ADR-013：金额全链路分）。 */
export function fenToYuanText(fen: string | number): string {
  let n: bigint
  try {
    n = BigInt(fen)
  } catch {
    return '¥--'
  }
  const neg = n < 0n
  const abs = neg ? -n : n
  const yuan = (abs / 100n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const cents = (abs % 100n).toString().padStart(2, '0')
  return `${neg ? '-' : ''}¥${yuan}.${cents}`
}
