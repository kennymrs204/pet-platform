import { employeesApi, type EmployeeRow } from '../api/employees'
import { BizError } from '../api/types'

/**
 * 批量排班候选的**独立**加载器（与员工管理页当前可见列表分别管理）。
 *
 * 为什么独立：员工管理页的 `rows` 受当前 tab / 关键词服务端过滤，直接拿它当候选会漏掉
 * 「没命中当前筛选但仍可排班」的员工（C9 审计缺陷）。这里恒以 `ALL` + 空关键词拉全店名册，
 * 候选资格由调用方用 `isScheduleEligible` 过滤，与当前可见列表完全解耦。
 *
 * 为什么是框架无关的模块（而非 React hook）：与 `lib/taskBadge.ts` / `lib/unreadCount.ts` 同一套
 * 「外部存储 + useSyncExternalStore」范式，可用项目既有 esbuild+node:test 探针对异步加载/切店/竞态
 * 做可控测试，无需 React-DOM 测试床。
 *
 * 竞态纪律：
 * - 单飞——同一门店在途请求复用，重复开关抽屉不产生重复请求；
 * - seq 守卫——切店 / 关闭 / 重开都会作废在途响应，旧门店或旧会话的晚到响应绝不覆盖新状态；
 * - 快照携带 storeId——调用方可再次核对，杜绝「沿用另一门店候选」。
 */

export type CandidatesState =
  | { phase: 'idle' }
  | { phase: 'loading'; storeId: string }
  | { phase: 'error'; storeId: string; message: string }
  | { phase: 'ready'; storeId: string; rows: EmployeeRow[] }

let seq = 0
let state: CandidatesState = { phase: 'idle' }
let inflight: Promise<void> | null = null
let inflightFor: string | null = null
const listeners = new Set<() => void>()

function commit(next: CandidatesState): void {
  state = next
  listeners.forEach((l) => l())
}

export function subscribeCandidates(fn: () => void): () => void {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}

export function getCandidates(): CandidatesState {
  return state
}

/** 关闭抽屉 / 登出 / 切店前：作废全部在途请求并清空，避免残留旧候选被下次打开复用。 */
export function resetCandidates(): void {
  seq++
  inflight = null
  inflightFor = null
  commit({ phase: 'idle' })
}

/**
 * 打开抽屉或切换门店时加载全店名册（`ALL`、空关键词）。
 * 返回的 Promise 在响应落定（或被 seq 作废）后 resolve；调用方通常经 subscribe 读快照，不依赖返回值。
 */
export function loadCandidates(storeId: string | null): Promise<void> {
  if (!storeId) {
    resetCandidates()
    return Promise.resolve()
  }
  // 单飞：同店已在途则复用，不重复请求、不 bump seq（否则会把自己的在途请求作废）
  if (inflight && inflightFor === storeId) {
    return inflight
  }
  // 切店 / 重新打开：作废旧店在途响应与旧快照
  const my = ++seq
  inflightFor = storeId
  commit({ phase: 'loading', storeId })
  // 用 Promise.resolve().then 包一层：http 客户端可能在发出请求前**同步**抛错
  // （如门店上下文缺失时的 NO_STORE_CTX 哨兵），归一到同一 onRejected，避免同步异常逃出 effect。
  const request = Promise.resolve()
    .then(() => employeesApi.list({ tab: 'ALL' }))
    .then(
      (rows) => {
        if (my === seq) {
          commit({ phase: 'ready', storeId, rows })
        }
      },
      (err: unknown) => {
        if (my !== seq) {
          return
        }
        commit({
          phase: 'error',
          storeId,
          message: err instanceof BizError ? err.message : '加载失败，请重试',
        })
      },
    )
    .finally(() => {
      if (inflight === request) {
        inflight = null
        inflightFor = null
      }
    })
  inflight = request
  return request
}
