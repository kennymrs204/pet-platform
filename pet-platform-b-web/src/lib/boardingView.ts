import type { StayStatus } from '../api/boarding'

/** 主列表每页条数（服务端钳制 1..100，这里只定 UI 缺省）。 */
export const BOARDING_PAGE_SIZE = 20
/** 待支付预约预览上限（StoreBoardingPickController.LEAD_LIMIT）。 */
export const PENDING_BOARDING_LIMIT = 100
/** 今日护理动态上限（BoardingStayService.ACTIVITY_LIMIT）。 */
export const CARE_ACTIVITY_LIMIT = 100
/** 单住单护理记录上限（BoardingStayService.CARE_LIST_LIMIT）。 */
export const CARE_RECORD_LIMIT = 200
/** 右栏动态卡预览条数。 */
export const ACTIVITY_PREVIEW_LIMIT = 6
/** 搜索输入防抖：逐字符打服务端会把一次输入变成十次查询。 */
export const KEYWORD_DEBOUNCE_MS = 350

export type BoardingTab = 'ALL' | 'IN_STORE' | StayStatus

/**
 * 页签 → 请求状态集合。与后端 `BoardingQueryApi` 的常量同一份口径：
 * 「在店中」= CHECKED_IN + DAILY_CARE，「全部」= 五种状态（含会另走历史端点的两个历史态）。
 * 前端只在这里描述"这个页签要哪些状态"，不再自己数状态。
 */
export const BOARDING_TAB_STATUSES: Record<BoardingTab, StayStatus[]> = {
  ALL: ['EXPECTED', 'CHECKED_IN', 'DAILY_CARE', 'CHECKED_OUT', 'ABNORMAL'],
  IN_STORE: ['CHECKED_IN', 'DAILY_CARE'],
  EXPECTED: ['EXPECTED'],
  CHECKED_IN: ['CHECKED_IN'],
  DAILY_CARE: ['DAILY_CARE'],
  CHECKED_OUT: ['CHECKED_OUT'],
  ABNORMAL: ['ABNORMAL'],
}

/** 列表请求的去重键：门店、页签、关键词、页码任一变化都必须重查，且不能串用旧结果。 */
export function stayPageQueryKey(
  storeId: string | null,
  tab: BoardingTab,
  keyword: string,
  pageNum: number,
): string | null {
  return storeId ? `${storeId}|${tab}|${keyword.trim()}|${pageNum}` : null
}

/** 角标请求键：与列表同门店同关键词，切页签不重取（角标本身覆盖全部页签）。 */
export function statusCountsKey(storeId: string | null, keyword: string, refreshTick: number): string | null {
  return storeId ? `${storeId}|${keyword.trim()}|${refreshTick}` : null
}

export function pageCount(total: number, pageSize: number): number {
  if (total <= 0 || pageSize <= 0) return 1
  return Math.max(1, Math.ceil(total / pageSize))
}

/**
 * 服务端返回后校正页码：删除/离店会让总条数变小，停在"曾经存在的第一页之后"就是空页。
 * 超出范围时回到最后一个有效页（而不是第 1 页）——用户翻页的上下文更有连续性。
 */
export function clampPageAfterLoad(pageNum: number, total: number, pageSize: number): number {
  const pages = pageCount(total, pageSize)
  if (pageNum <= pages) return pageNum
  return total <= 0 ? 1 : pages
}

/** 触顶计数：返回「≥N」，避免把截断后的条数说成全量（页签角标已改由服务端精确计数，这里只剩右栏卡）。 */
export function boundedCountText(count: number, approximate: boolean): string {
  return approximate ? `≥${count}` : String(count)
}
