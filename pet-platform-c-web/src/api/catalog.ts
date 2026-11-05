import { http } from './client'

/**
 * C 端门店与服务浏览。两个接口分属两种状态，务必区别对待：
 *  - GET /public/stores（store 第二段，ADR-022）**已上线** → 由 lib/usePublicStores 取真实数据；
 *    api/store 的 DEMO_STORES 只在请求失败时兜底，页面据此黄条明示。
 *  - GET /customer/store-services（第一段 catalog，ADR-010）已上线 → 真实数据，
 *    storeId 必填、后端强制只返回 status=ON 的上架服务。
 * 价格 price 为分且后端 Long 序列化为字符串，展示前统一 Number 化。
 */
export interface StoreServiceVO {
  id: string
  storeId: string
  serviceId: string
  serviceCode: string
  serviceName: string
  description: string | null
  category: string
  price: string | number
  durationMin: number | null
  fulfillmentType: 'INSTANT' | 'MULTI_DAY' | string
  requiredResourceType: string | null
  allowUserPickEmployee: boolean | null
  allowUserPickEmployeeOverride: boolean | null
  cancelFreeHours: number | null
  cancelPenaltyRate: number | null
  status: string
  skills: { skillId: string; skillName?: string; required?: boolean }[]
}

export interface PageResult<T> {
  total: string | number
  pageNum: number
  pageSize: number
  list: T[]
}

export const catalogApi = {
  listStoreServices: (storeId: string, opts: { keyword?: string; pageSize?: number; signal?: AbortSignal } = {}) => {
    const q = new URLSearchParams({ storeId, pageNum: '1', pageSize: String(opts.pageSize ?? 50) })
    if (opts.keyword) q.set('keyword', opts.keyword)
    return http.get<PageResult<StoreServiceVO>>(`/customer/store-services?${q.toString()}`, {
      auth: true,
      signal: opts.signal,
    })
  },
}

/** 分转元：金额链路全程用分，只在展示层转换（BigInt 防浮点误差）。 */
export function fenToYuan(fen: string | number | null | undefined): string {
  if (fen === null || fen === undefined || fen === '') return '—'
  const n = BigInt(String(fen))
  const yuan = n / 100n
  const rest = n % 100n
  return rest === 0n ? String(yuan) : `${yuan}.${String(rest).padStart(2, '0')}`
}

/**
 * 日单价 × 晚数（ADR-025）。只用于**下单前预估**——最终金额一律以后端创建返回的 amount 为准，
 * 前端乘法不构成账目。
 */
export function totalFen(unitFen: string | number, times: number): string {
  return String(BigInt(Math.round(Number(unitFen))) * BigInt(Math.max(1, times)))
}
