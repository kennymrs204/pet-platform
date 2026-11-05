import { http } from './client'
import { BizCode, BizError } from './types'
import type { PageResult } from './catalog'
import { addDaysISO, todayISO } from '../lib/dateText'

/**
 * C 端托管（寄养）与每日护理日报。
 *
 * 真实契约（ADR-015 §4，后端 CustomerBoardingController 已实现）：
 *   GET /api/v1/customer/boarding-stays/{id}                        → CustomerStayView
 *   GET /api/v1/customer/boarding-stays/{id}/care-records?startDate=&endDate= → CustomerCareRecordView[]
 *   权限码 boarding:care:view（CUSTOMER 已持有）；授权按宠物归属 canManage；
 *   不存在/已删/非归属统一 10004（按不存在处理，不泄露存在性）。
 *   响应刻意最小化：不返回 kennelResourceId / 员工 id / appointmentId / 门店内部字段。
 *
 * **缺口已由 ADR-021 补齐**（2026-09-26 实测通过）：
 *   GET /api/v1/customer/boarding-stays?status=&pageNum=&pageSize= → PageResult<CustomerStayView>
 *   status 支持**逗号多值**（非法值 10001，空=全部）；排序 checkin_date DESC → id DESC 由后端负责；
 *   视图与 detail 同款最小化（不含 appointmentId/storeId/kennelResourceId）。
 *   归属推导走 AppointmentQueryApi.boardingAppointmentIdsOfUser，不 JOIN 不加列（ADR-021 §1）。
 * 仍保留"集合路径 10004 → 演示数据"的兜底，只为兼容**未含该构建的旧容器**；新构建下不会触发。
 */

export type StayStatus = 'EXPECTED' | 'CHECKED_IN' | 'DAILY_CARE' | 'CHECKED_OUT' | 'ABNORMAL'

export interface CustomerStayView {
  id: string
  petId: string
  status: StayStatus
  checkinDate: string
  checkoutDate: string
  actualCheckinAt: string | null
  actualCheckoutAt: string | null
  specialInstructions: string | null
  caretakerName: string | null
}

export interface CareFeeding {
  count: number | null
  amountText: string | null
}
export interface CareWatering {
  count: number | null
}
export interface CareWalk {
  count: number | null
  durationMin: number | null
}

export interface CustomerCareRecordView {
  id: string
  careDate: string
  feeding: CareFeeding | null
  watering: CareWatering | null
  walk: CareWalk | null
  observation: string | null
  photos: string[] | null
  abnormalFlag: boolean | null
  abnormalDesc: string | null
}

export const STAY_STATUS_TEXT: Record<StayStatus, string> = {
  EXPECTED: '待入住',
  CHECKED_IN: '已入住',
  DAILY_CARE: '日常照护中',
  CHECKED_OUT: '已离店',
  ABNORMAL: '状态异常',
}

/** 页签 → 状态集合：真实端点按逗号多值一次取齐（ADR-021 §4），演示回退时同口径本地过滤。 */
export const STAY_TABS: { key: string; label: string; statuses: StayStatus[] }[] = [
  { key: 'ALL', label: '全部', statuses: [] },
  { key: 'EXPECTED', label: '待入住', statuses: ['EXPECTED'] },
  { key: 'IN_STORE', label: '在店', statuses: ['CHECKED_IN', 'DAILY_CARE'] },
  { key: 'CHECKED_OUT', label: '已离店', statuses: ['CHECKED_OUT'] },
]

export function stayStatusTone(status: StayStatus): string {
  if (status === 'EXPECTED') return 'bg-gold/15 text-gold-dark ring-1 ring-gold/40'
  if (status === 'CHECKED_OUT') return 'bg-navy/8 text-navy/70 ring-1 ring-navy/10'
  if (status === 'ABNORMAL') return 'bg-rose-50 text-rose-600 ring-1 ring-rose-200/70'
  return 'bg-emerald-50 text-emerald-600 ring-1 ring-emerald-200/70'
}

/** 在住第几天（入住当天=1）；未到期显示倒计时；已离店显示总天数；日期异常不猜。 */
export function stayDayNo(stay: CustomerStayView, today = todayISO()): string {
  if (stay.status === 'CHECKED_OUT') {
    const total = diffDays(stay.checkinDate, stay.checkoutDate)
    return total === null ? '—' : `共 ${total + 1} 天`
  }
  const untilCheckin = diffDays(today, stay.checkinDate)
  if (untilCheckin === null) return '—'
  if (untilCheckin > 0) return `还有 ${untilCheckin} 天入住`
  const passed = diffDays(stay.checkinDate, today)
  return passed === null ? '—' : `第 ${passed + 1} 天`
}

function diffDays(from: string | null, to: string | null): number | null {
  if (!from || !to) return null
  const a = Date.parse(`${from}T00:00:00`)
  const b = Date.parse(`${to}T00:00:00`)
  if (Number.isNaN(a) || Number.isNaN(b)) return null
  return Math.round((b - a) / 86400000)
}

/* ------------------------------ 演示数据（列表接口未上线时） ------------------------------ */

const T = todayISO()
const at = (date: string, hm: string) => `${date}T${hm}:00`

export const DEMO_STAYS: CustomerStayView[] = [
  {
    id: '9001',
    petId: 'demo-pet-mochai',
    status: 'DAILY_CARE',
    checkinDate: addDaysISO(T, -2),
    checkoutDate: addDaysISO(T, 2),
    actualCheckinAt: at(addDaysISO(T, -2), '10:12'),
    actualCheckoutAt: null,
    specialInstructions: '怕吹风机；狗粮换粮需 3 天过渡，勿喂禽类蛋白（过敏）。',
    caretakerName: '陈护理',
  },
  {
    id: '9002',
    petId: 'demo-pet-xiaomai',
    status: 'CHECKED_IN',
    checkinDate: T,
    checkoutDate: addDaysISO(T, 3),
    actualCheckinAt: at(T, '09:40'),
    actualCheckoutAt: null,
    specialInstructions: '每天下午 15:00 需要一次药浴，猫砂每日清理两次。',
    caretakerName: '李护理',
  },
  {
    id: '9003',
    petId: 'demo-pet-doudou',
    status: 'EXPECTED',
    checkinDate: addDaysISO(T, 4),
    checkoutDate: addDaysISO(T, 7),
    actualCheckinAt: null,
    actualCheckoutAt: null,
    specialInstructions: null,
    caretakerName: null,
  },
  {
    id: '9004',
    petId: 'demo-pet-mochai',
    status: 'CHECKED_OUT',
    checkinDate: addDaysISO(T, -12),
    checkoutDate: addDaysISO(T, -9),
    actualCheckinAt: at(addDaysISO(T, -12), '10:02'),
    actualCheckoutAt: at(addDaysISO(T, -9), '16:31'),
    specialInstructions: '老年犬，关节不便，避免上下楼梯。',
    caretakerName: '陈护理',
  },
]

/**
 * 演示住单的宠物名——**只存在于本文件的展示侧映射**，不塞进 CustomerStayView
 * （真实契约只给 petId，名字应由前端 join /customer/pets 得到）。
 */
export const DEMO_PET_LABEL: Record<string, string> = {
  'demo-pet-mochai': '摩卡（演示）',
  'demo-pet-xiaomai': '小麦（演示）',
  'demo-pet-doudou': '豆豆（演示）',
}

const DEMO_RECORDS: Record<string, CustomerCareRecordView[]> = {
  '9001': [
    {
      id: '9101',
      careDate: addDaysISO(T, -2),
      feeding: { count: 2, amountText: '每餐约 60g 幼犬粮' },
      watering: { count: 4 },
      walk: { count: 2, durationMin: 30 },
      observation: '入住首日适应良好，食欲正常，排便成型。',
      photos: ['/images/demo/care-dog.png'],
      abnormalFlag: false,
      abnormalDesc: null,
    },
    {
      id: '9102',
      careDate: addDaysISO(T, -1),
      feeding: { count: 2, amountText: '每餐约 60g，餐后轻度胀气' },
      watering: { count: 3 },
      walk: { count: 3, durationMin: 45 },
      observation: '傍晚遛放后右前爪出现跛行，已请店内医生查看并通知家长。',
      photos: [],
      abnormalFlag: true,
      abnormalDesc: '右前爪跛行，触诊趾间有轻微红肿；已限制跑动并转医生处理。',
    },
    {
      id: '9103',
      careDate: T,
      feeding: { count: 1, amountText: '早餐正常，午餐暂未食用' },
      watering: { count: 2 },
      walk: { count: 1, durationMin: 20 },
      observation: '趾间红肿较昨日减轻，按医嘱改为短距慢遛。',
      photos: [],
      abnormalFlag: false,
      abnormalDesc: null,
    },
  ],
  '9002': [
    {
      id: '9104',
      careDate: T,
      feeding: { count: 1, amountText: '湿粮半罐' },
      watering: { count: 2 },
      walk: { count: 0, durationMin: 0 },
      observation: '入住后先适应猫舍，午休为主。',
      photos: ['/images/demo/care-cat.png'],
      abnormalFlag: false,
      abnormalDesc: null,
    },
  ],
  '9004': [
    {
      id: '9105',
      careDate: addDaysISO(T, -12),
      feeding: { count: 2, amountText: '处方粮 80g/餐' },
      watering: { count: 3 },
      walk: { count: 2, durationMin: 15 },
      observation: '慢遛为主，未上楼梯。',
      photos: [],
      abnormalFlag: false,
      abnormalDesc: null,
    },
    {
      id: '9106',
      careDate: addDaysISO(T, -11),
      feeding: { count: 2, amountText: '处方粮 80g/餐，食欲好' },
      watering: { count: 4 },
      walk: { count: 2, durationMin: 20 },
      observation: '精神状态良好，夜间安睡。',
      photos: [],
      abnormalFlag: false,
      abnormalDesc: null,
    },
    {
      id: '9107',
      careDate: addDaysISO(T, -10),
      feeding: { count: 2, amountText: '处方粮 80g/餐' },
      watering: { count: 3 },
      walk: { count: 3, durationMin: 20 },
      observation: '离店前洗澡护理已完成。',
      photos: [],
      abnormalFlag: false,
      abnormalDesc: null,
    },
  ],
}

/* --------------------------------- 调用封装 --------------------------------- */

export interface StayListResult {
  list: CustomerStayView[]
  /** true = 列表接口未上线，当前为本地演示数据 */
  demo: boolean
}

export interface StayDetailResult {
  stay: CustomerStayView
  demo: boolean
}

export interface CareRecordsResult {
  records: CustomerCareRecordView[]
  demo: boolean
}

/**
 * 判断「住单列表端点缺失」——`GET /customer/boarding-stays` 已接线，此兜底只用于兼容
 * 未升级的旧容器。本项目 HTTP 恒 200，未映射路径由 GlobalExceptionHandler
 * 归一成 code=10004，所以集合路径（不带 id）上的 10004 只可能是"端点不存在"，
 * 不会与 detail 上"这条住单非你所属"的 10004 混淆——那个必须照原样抛给页面。
 */
function endpointNotWired(e: unknown): boolean {
  if (!(e instanceof BizError)) return false
  return e.code === BizCode.NOT_FOUND || (e.code === BizCode.NETWORK && /404/.test(e.message))
}

const isDemoId = (id: string) => DEMO_STAYS.some((s) => s.id === id)

export const stayApi = {
  /** 我的住单列表：status 逗号多值一次取齐（ADR-021 §4）；旧容器无此端点时回退演示数据。 */
  async list(statuses: StayStatus[] = [], signal?: AbortSignal): Promise<StayListResult> {
    const q = new URLSearchParams({ pageNum: '1', pageSize: '50' })
    if (statuses.length > 0) q.set('status', statuses.join(','))
    try {
      const page = await http.get<PageResult<CustomerStayView>>(`/customer/boarding-stays?${q.toString()}`, {
        auth: true,
        signal,
      })
      return { list: page.list ?? [], demo: false }
    } catch (e) {
      if (!endpointNotWired(e)) throw e
      const list = statuses.length === 0 ? DEMO_STAYS : DEMO_STAYS.filter((s) => statuses.includes(s.status))
      return { list, demo: true }
    }
  },

  async detail(id: string, signal?: AbortSignal): Promise<StayDetailResult> {
    if (isDemoId(id)) {
      const stay = DEMO_STAYS.find((s) => s.id === id)
      if (!stay) throw new BizError(BizCode.NOT_FOUND, '找不到这条托管记录')
      return { stay, demo: true }
    }
    return { stay: await http.get<CustomerStayView>(`/customer/boarding-stays/${id}`, { auth: true, signal }), demo: false }
  },

  async careRecords(id: string, startDate?: string, endDate?: string): Promise<CareRecordsResult> {
    if (isDemoId(id)) {
      const all = DEMO_RECORDS[id] ?? []
      const from = startDate ?? ''
      const to = endDate ?? '9999-12-31'
      return { records: all.filter((r) => r.careDate >= from && r.careDate <= to), demo: true }
    }
    const q = new URLSearchParams()
    if (startDate) q.set('startDate', startDate)
    if (endDate) q.set('endDate', endDate)
    const qs = q.toString()
    const records = await http.get<CustomerCareRecordView[]>(
      `/customer/boarding-stays/${id}/care-records${qs ? `?${qs}` : ''}`,
      { auth: true },
    )
    return { records: Array.isArray(records) ? records : [], demo: false }
  },
}
