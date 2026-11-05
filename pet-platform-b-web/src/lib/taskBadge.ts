import { useEffect, useSyncExternalStore } from 'react'
import { tasksApi } from '../api/tasks'
import { storeContext } from '../auth/storeContext'
import { parseTodoTotal } from './taskBadgeRules'

/**
 * 我的任务导航角标：当前员工在当前门店、当天 TODO（CONFIRMED/WAIT_SERVICE/SERVING）的真实总数。
 *
 * 数字来自 `/store/tasks/mine` 的 `todoTotal`，不是列表长度、不是消息未读、不是聊天未读。
 * 后端已按 token 员工 + X-Store-Id + 日期过滤，todoTotal 不受任务页 status 筛选影响。
 */

/** TasksPage 原有日期口径：UTC ISO 日期；抽到共享模块，禁止另造本地时区算法。 */
export const taskTodayISO = (): string => new Date().toISOString().slice(0, 10)

let cached: number | null = null
let cachedFor: string | null = null
let loadedAt = 0
let inflight: Promise<void> | null = null
let inflightFor: string | null = null
let revision = 0
const listeners = new Set<() => void>()

function currentScope(): string | null {
  const storeId = storeContext.currentStoreId()
  return storeId ? `${storeId}:${taskTodayISO()}` : null
}

function publish(scope: string | null, value: number | null, fresh = false) {
  cachedFor = scope
  cached = value
  loadedAt = fresh ? Date.now() : 0
  revision += 1
  listeners.forEach((listener) => listener())
}

export function getTaskBadgeSnapshot(): number | null {
  const scope = currentScope()
  return scope !== null && scope === cachedFor ? cached : null
}

/**
 * 刷新角标。single-flight + revision 守卫：旧门店/旧请求晚返回不会覆盖当前门店。
 * force=false 时允许 2s 内的同 scope 缓存，避免 Layout 因路由切换重挂载就重复请求。
 */
export function refreshTaskBadge(force = false): Promise<void> {
  const scope = currentScope()
  if (!scope) {
    inflightFor = null
    publish(null, null)
    return Promise.resolve()
  }
  if (inflight && inflightFor === scope) return inflight
  if (!force && cachedFor === scope && cached !== null && Date.now() - loadedAt < 2_000) {
    return Promise.resolve()
  }
  if (cachedFor !== scope) publish(scope, null)

  const requestRevision = revision
  const request = tasksApi
    .mine({ date: taskTodayISO() })
    .then((view) => {
      if (revision !== requestRevision || inflightFor !== scope) return
      const todoTotal = parseTodoTotal(view?.todoTotal)
      if (todoTotal === null) throw new Error('todoTotal missing or invalid')
      publish(scope, todoTotal, true)
    })
    .catch(() => {
      if (revision === requestRevision && inflightFor === scope) publish(scope, null)
    })
    .finally(() => {
      if (inflight === request) {
        inflight = null
        inflightFor = null
      }
    })
  inflight = request
  inflightFor = scope
  return request
}

/** 任务页加载成功时把同一口径的 todoTotal 直接写回共享角标（页面在非今天日期时不写）。 */
export function applyTaskBadgeSnapshot(
  storeId: string | null,
  date: string | null | undefined,
  rawTodoTotal: string | number | null | undefined,
) {
  const currentStore = storeContext.currentStoreId()
  if (!storeId || !currentStore || currentStore !== storeId || date !== taskTodayISO()) return
  const scope = `${storeId}:${date}`
  const todoTotal = parseTodoTotal(rawTodoTotal)
  publish(scope, todoTotal, todoTotal !== null)
}

export function resetTaskBadge() {
  inflightFor = null
  publish(null, null)
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  const offStore = storeContext.subscribe(() => {
    void refreshTaskBadge(true)
    listener()
  })
  return () => {
    listeners.delete(listener)
    offStore()
  }
}

const TASK_BADGE_POLL_MS = 45_000

export function useTaskBadge(): number | null {
  const count = useSyncExternalStore(subscribe, getTaskBadgeSnapshot)
  useEffect(() => {
    void refreshTaskBadge()
    const onVisibility = () => {
      if (document.visibilityState === 'visible') void refreshTaskBadge(true)
    }
    document.addEventListener('visibilitychange', onVisibility)
    // 与 unreadCount 同一节奏；隐藏页不请求，开始/完成由任务页响应即时回写，
    // 定时刷新主要兜住"别处取消/改派"这类没有本地事件的场景。
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void refreshTaskBadge(true)
    }, TASK_BADGE_POLL_MS)
    return () => {
      document.removeEventListener('visibilitychange', onVisibility)
      window.clearInterval(timer)
    }
  }, [])
  return count
}
