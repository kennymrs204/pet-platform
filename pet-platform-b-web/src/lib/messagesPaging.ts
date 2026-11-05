/**
 * 消息中心分页的请求上下文护栏。
 *
 * 「加载更多」在发起时捕获 (storeId, seq)；响应回来时只有二者仍与当前上下文一致，才允许追加列表、
 * 推进 total/pageNum。换门店或页面卸载时 seq 自增，作废所有在途请求——即使底层 AbortSignal 未及时生效，
 * 旧门店的迟到响应也写不进新门店的列表、分页游标、loading 或错误状态。
 */

export interface PagingToken {
  storeId: string
  seq: number
}

/** 请求发起时的上下文是否仍是当前上下文（门店 + 代次双重比对；任一不符即失效）。 */
export function isPagingTokenCurrent(
  token: PagingToken,
  currentStoreId: string | null,
  currentSeq: number,
): boolean {
  return token.storeId === currentStoreId && token.seq === currentSeq
}

export type LoadMorePlan<T> =
  | { applied: false }
  | { applied: true; append: T[]; total: number; pageNum: number }

/**
 * 「加载更多」的提交闸：上下文有效才产出要追加的一页与新的 total/pageNum；
 * 失效一律 `{ applied: false }`，调用方不得写任何状态（append 用函数式并入当前列表，保留并发本地改动）。
 */
export function planLoadMoreCommit<T>(
  token: PagingToken,
  currentStoreId: string | null,
  currentSeq: number,
  page: { list?: T[] | null; total?: number | string | null },
  nextPage: number,
): LoadMorePlan<T> {
  if (!isPagingTokenCurrent(token, currentStoreId, currentSeq)) {
    return { applied: false }
  }
  return {
    applied: true,
    append: page.list ?? [],
    total: Number(page.total ?? 0),
    pageNum: nextPage,
  }
}
