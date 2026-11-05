import { http } from './client'
import type { PageData } from './dashboard'

/**
 * B 端设置页接口层（ADR-020 + 已实现的 catalog 价目端点）。
 * 全部直连真实后端，**不走演示数据兜底**：设置页写错一次就可能让门店停业或改坏价目，
 * 假数据掩盖的代价远高于"暂时打不开"。
 *
 * 权限可见性（V2 种子）：
 * - store:info:update / resource:manage / catalog:store_service:manage → ADMIN + 店长；**前台与任务角色一律 10003**；
 * - 平台参数（sys_config）只随 GET /store/info 只读透出；ADMIN 侧的 /admin/configs 写端点尚未实现（全仓无该路由）；
 * - store_type 与 status 不在可写字段内（提权与停业面，ADR-020 §3）。
 */

export type StoreStatus = 'OPEN' | 'SUSPENDED' | 'CLOSED'
export type ResourceType =
  | 'BATH_STATION'
  | 'GROOMING_STATION'
  | 'EXAM_ROOM'
  | 'KENNEL_SMALL'
  | 'KENNEL_MEDIUM'
  | 'KENNEL_LARGE'
  | 'CAT_ROOM'
export type ResourceStatus = 'AVAILABLE' | 'MAINTENANCE' | 'DISABLED'

export interface StoreInfo {
  id: string
  name: string
  phone: string
  address: string
  /**
   * DECIMAL 列：JacksonConfig 只字符串化 Long，BigDecimal 原样输出数字，
   * 所以运行时是 number 而非 string（库里当前全 NULL，勿据此以为可以省）。
   */
  latitude: string | number | null
  longitude: string | number | null
  businessStartTime: string
  businessEndTime: string
  breakStartTime: string | null
  breakEndTime: string | null
  status: StoreStatus | string
  storeType: string
  announcement: string | null
}

/**
 * PUT 是全量写（`StoreInfoMapper.updateInfo` 走显式列名 SQL，为的就是能把午休/公告真的写回 NULL），
 * 因此**没出现在 body 里的可写列会被写成 NULL**。latitude/longitude 本页无输入项，
 * 必须把 GET 的原值回传，否则每次保存门店资料都会清空坐标。
 */
export interface StoreInfoUpdate {
  name: string
  phone: string
  address: string
  latitude?: string | number | null
  longitude?: string | number | null
  businessStartTime: string
  businessEndTime: string
  /** 起止必须成对，只给一头后端 10001 */
  breakStartTime: string | null
  breakEndTime: string | null
  announcement: string | null
}

export interface PlatformParam {
  key: string
  value: string
  remark: string | null
  /** false = 当前无任何 Java 读取该 key，展示值不生效（ADR-020 §2） */
  consumed: boolean
}

export interface StoreSettings {
  store: StoreInfo
  platformParams: PlatformParam[]
}

export interface ResourceItem {
  id: string
  storeId: string
  type: ResourceType | string
  name: string
  status: ResourceStatus | string
}

export interface StoreServiceItem {
  id: string
  storeId: string
  serviceId: string
  serviceCode: string | null
  serviceName: string | null
  description: string | null
  category: string | null
  /** 分（Long→字符串） */
  price: string
  durationMin: number
  fulfillmentType: string | null
  requiredResourceType: string | null
  /** 已解析的最终值（门店覆盖优先，否则继承平台默认）——只用于展示。 */
  allowUserPickEmployee: boolean | null
  /** 门店覆盖列原值：null=继承。写侧必须回传这个，回传上面那个会把「继承」固化成「覆盖」。 */
  allowUserPickEmployeeOverride: boolean | null
  cancelFreeHours: number
  cancelPenaltyRate: number
  status: 'ON' | 'OFF' | string
}

/** PUT 是全量 upsert：五个 @NotNull 字段必须回传（值取自当前行，不重新发明）。 */
export interface StoreServiceUpdate {
  serviceId: string
  price: string
  durationMin: number
  requiredResourceType?: string | null
  /** 传 `allowUserPickEmployeeOverride`，不是 `allowUserPickEmployee`（见上）。 */
  allowUserPickEmployee?: boolean | null
  cancelFreeHours: number
  cancelPenaltyRate: number
  status?: string
}

export const settingsApi = {
  info: () => http.get<StoreSettings>('/store/info', { auth: true }),
  updateInfo: (body: StoreInfoUpdate) => http.put<StoreInfo>('/store/info', body, { auth: true }),

  resources: () => http.get<ResourceItem[]>('/store/resources', { auth: true }),
  createResource: (type: string, name: string) =>
    http.post<ResourceItem>('/store/resources', { type, name }, { auth: true }),
  updateResource: (id: string, body: { name?: string; status?: string }) =>
    http.put<ResourceItem>(`/store/resources/${id}`, body, { auth: true }),

  storeServices: (status?: string) =>
    http.get<PageData<StoreServiceItem>>(
      `/store/store-services?pageSize=50${status ? `&status=${status}` : ''}`,
      { auth: true },
    ),
  updateStoreService: (id: string, body: StoreServiceUpdate) =>
    http.put<StoreServiceItem>(`/store/store-services/${id}`, body, { auth: true }),
}

export const RESOURCE_TYPE_LABEL: Record<ResourceType, string> = {
  BATH_STATION: '洗澡台',
  GROOMING_STATION: '美容台',
  EXAM_ROOM: '诊室',
  KENNEL_SMALL: '小笼位',
  KENNEL_MEDIUM: '中笼位',
  KENNEL_LARGE: '大笼位',
  CAT_ROOM: '猫舍',
}
export const RESOURCE_TYPES = Object.keys(RESOURCE_TYPE_LABEL) as ResourceType[]

export const RESOURCE_STATUS_LABEL: Record<ResourceStatus, string> = {
  AVAILABLE: '可用',
  MAINTENANCE: '维护中',
  DISABLED: '已停用',
}
export const RESOURCE_STATUSES = Object.keys(RESOURCE_STATUS_LABEL) as ResourceStatus[]

export const STORE_STATUS_LABEL: Record<string, string> = {
  OPEN: '营业中',
  SUSPENDED: '暂停营业',
  CLOSED: '已关店',
}

/** 「12:00:00」→「12:00」；无值返回空串供 input[type=time] 使用。 */
export function timeInputValue(t: string | null): string {
  return t ? t.slice(0, 5) : ''
}

/** input[type=time] 的「12:00」→ 后端 LocalTime「12:00:00」。 */
export function toLocalTime(v: string): string | null {
  return v ? `${v.length === 5 ? `${v}:00` : v}` : null
}

/** 密码规则（ADR-009：8-20 位、含字母与数字、无空白），前端先拦，后端仍是最终裁判。 */
export function passwordProblem(pwd: string): string | null {
  if (pwd.length < 8 || pwd.length > 20) return '密码需 8-20 位'
  if (/\s/.test(pwd)) return '密码不能包含空格或空白字符'
  if (!/[A-Za-z]/.test(pwd)) return '密码需包含字母'
  if (!/\d/.test(pwd)) return '密码需包含数字'
  return null
}

/** 门店资料表单态（input[type=time] 用 HH:mm，公告空串=清空）。 */
export interface StoreInfoFormState {
  name: string
  phone: string
  address: string
  businessStart: string
  businessEnd: string
  breakStart: string
  breakEnd: string
  announcement: string
}

/**
 * 门店资料表单校验（与后端 `StoreInfoService.update` 同规则，前端先拦、后端仍是最终裁判）。
 * 放在接口层而不是页面里，是为了让"午休必须落在营业窗内"这类跨字段规则能被断言锁住。
 */
export function storeInfoProblem(f: StoreInfoFormState): string | null {
  if (!f.name.trim()) return '门店名称不能为空'
  if (!f.phone.trim()) return '联系电话不能为空'
  if (!f.address.trim()) return '门店地址不能为空'
  if (!f.businessStart || !f.businessEnd) return '营业起止时间必填'
  if (f.businessStart >= f.businessEnd) return '营业开始必须早于结束时间'
  if (!!f.breakStart !== !!f.breakEnd) return '休息时段起止需成对填写'
  if (f.breakStart && f.breakEnd && f.breakStart >= f.breakEnd) return '休息开始必须早于休息结束'
  // HH:mm 定长字符串比较即时序比较；边界算在内（贴开门/打烊的休息段是合法诉求）
  if (f.breakStart && f.breakEnd && (f.breakStart < f.businessStart || f.breakEnd > f.businessEnd)) {
    return '休息时段须落在营业时间内'
  }
  if (f.announcement.length > 500) return '门店公告最多 500 字'
  return null
}

/**
 * 组门店资料 PUT 体。抽成纯函数的理由只有一个：**PUT 是显式列名全量写**，
 * 少带一个键就等于把它写成 NULL——坐标这类"页面上没有输入项"的字段最容易掉。
 */
export function buildStoreInfoUpdate(store: StoreInfo, f: StoreInfoFormState): StoreInfoUpdate {
  return {
    name: f.name.trim(),
    phone: f.phone.trim(),
    address: f.address.trim(),
    latitude: store.latitude,
    longitude: store.longitude,
    businessStartTime: toLocalTime(f.businessStart)!,
    businessEndTime: toLocalTime(f.businessEnd)!,
    breakStartTime: toLocalTime(f.breakStart),
    breakEndTime: toLocalTime(f.breakEnd),
    announcement: f.announcement.trim() || null,
  }
}

/**
 * 组价目 PUT 体（后端是全量 upsert）。两条必须成立：
 * ① 可指定员工回传**覆盖列原值** `allowUserPickEmployeeOverride`，不是已解析的最终值——
 *    回传最终值会把「继承平台默认」固化成「门店显式覆盖」，而本页没有恢复继承的入口；
 * ② 取消政策/资源类型/状态等未编辑的列原样带回。
 */
export function buildStoreServiceUpdate(
  item: StoreServiceItem,
  draft: PricingDraft,
  status?: string,
): StoreServiceUpdate {
  return {
    serviceId: item.serviceId,
    price: String(Math.round(Number(draft.price) * 100)),
    durationMin: Number(draft.durationMin),
    requiredResourceType: item.requiredResourceType,
    allowUserPickEmployee: draft.pick ? overrideOfPickMode(draft.pick) : item.allowUserPickEmployeeOverride,
    cancelFreeHours: item.cancelFreeHours,
    cancelPenaltyRate: item.cancelPenaltyRate,
    status,
  }
}

/**
 * 「可指定员工」的三态：覆盖列是**可空布尔**，null 不是"没填"而是"继承平台默认"，
 * 所以控件必须是三态而非开关——两态控件永远回不到继承（这是 B1 的由来）。
 */
export type PickMode = 'INHERIT' | 'ALLOW' | 'DENY'

export const PICK_MODE_LABEL: Record<PickMode, string> = {
  INHERIT: '继承平台默认',
  ALLOW: '允许顾客指定',
  DENY: '不允许顾客指定',
}
export const PICK_MODES = Object.keys(PICK_MODE_LABEL) as PickMode[]

export function pickModeOfOverride(override: boolean | null): PickMode {
  if (override === null) return 'INHERIT'
  return override ? 'ALLOW' : 'DENY'
}

export function overrideOfPickMode(mode: PickMode): boolean | null {
  if (mode === 'INHERIT') return null
  return mode === 'ALLOW'
}

/** 价目行的草稿态：价格/时长是文本输入，pick 是三态选择。 */
export interface PricingDraft {
  price: string
  durationMin: string
  pick?: PickMode
}

/** sch_resource 无 (store_id,type,name) 唯一索引、POST 也无 requestId 幂等 → 同名同类型先本地拦。 */
export function isDuplicateResource(items: ResourceItem[], type: string, name: string): boolean {
  const trimmed = name.trim()
  if (!trimmed) return false
  return items.some((r) => r.type === type && r.name === trimmed)
}

/** 未生效参数清单现算，别写死名字：每接一个新消费方，写死的清单就会让页面撒谎。 */
export function unconsumedParamKeys(params: PlatformParam[]): string[] {
  return params.filter((p) => !p.consumed).map((p) => p.key)
}
