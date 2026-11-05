import { useCallback, useEffect, useState } from 'react'
import { chatApi } from '../api/chat'
import { storeContext } from '../auth/storeContext'
import { tokenStore } from '../auth/tokenStore'

/**
 * 未读数的模块级单例（ADR-047；P4 起数字源切换为 /store/unread-summary）。
 *
 * 为什么不能像 C 端那样"一个数走天下"：B 端的未读数**按门店上下文算**
 * （后端 `WHERE user_id=我 AND store_id=当前 X-Store-Id`）。实测 DemoManager 同属 9111 与 9112，
 * 若缓存不记它是哪个店的，切到店长视角时铃铛会挂着上一店的数字——那是假信息，比不显示更糟。
 * 缓存 scope 因此必须同时包含**当前用户 + 当前门店**：只记 storeId 会让同店的下一个账号复用上一个人的数字。
 *
 * 身份/竞态：reset/换账号/换店以 revision 使在途请求失效；请求在 then/catch/finally 都按
 * revision + user:store scope + 自身 Promise 身份复核。旧响应不能写入新 scope，旧 finally
 * 不能清掉新请求的 inflight。失败保留同一 scope 上次成功值；首次失败保持 null（未知），不伪造 0。
 *
 * 刷新时机：挂载即拉（进任意页面都会，因为铃铛在共享 Layout 上）、已读/全部已读后显式刷新、
 * 切店时经 storeContext 订阅刷新，以及**会话级轮询单例**（ADR-056：每 45s 拉一次，页面隐藏时
 * 不打网络、切回前台立即补一次）。只做轮询，不做长连接/WebSocket/短信。
 */

export interface UnreadSummary {
  notificationCount: number
  chatCount: number
  total: number
  /** 消息中心列表完整未读（含 CHAT_MESSAGE）；铃铛 total/notificationCount 不受它影响 */
  listUnreadCount: number
}

let cached: UnreadSummary | null = null
let cachedFor: string | null = null
let inflight: Promise<void> | null = null
let inflightFor: string | null = null
let revision = 0
const listeners = new Set<(value: UnreadSummary | null) => void>()

/** user + store 缺一不可；store 未就绪时返回 null，绝不把"身份未知"当成 0 号店。 */
function currentScope(): string | null {
  const userId = tokenStore.getUserId()
  const storeId = storeContext.currentStoreId()
  return userId && storeId ? `${userId}:${storeId}` : null
}

function readCurrent(): UnreadSummary | null {
  return cachedFor === currentScope() ? cached : null
}

function emit() {
  const value = readCurrent()
  listeners.forEach((fn) => fn(value))
}

function write(scope: string | null, value: UnreadSummary | null) {
  cachedFor = scope
  cached = value
  emit()
}

/** Long 字符串化入参的最小心跳：非有限数一律归 0（不把 NaN/对象当计数展示）。 */
function toCount(v: string | number | null | undefined): number {
  const n = Number(v)
  return Number.isFinite(n) && n > 0 ? n : 0
}

/** 当前用户+门店下最后一次成功值；null 表示未知。 */
export function getUnreadCountSnapshot(): UnreadSummary | null {
  return readCurrent()
}

export function subscribeUnreadCount(listener: (value: UnreadSummary | null) => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** 拉当前身份+门店的未读摘要；同 scope 并发共用一发。 */
export function refreshUnreadCount(): Promise<void> {
  const scope = currentScope()
  if (!scope) {
    resetUnreadCount()
    return Promise.resolve()
  }
  if (inflight && inflightFor === scope) return inflight
  // 换店或换账号：先把旧 scope 的数字作废，绝不让它在新上下文下短暂可见
  if (cachedFor !== scope) write(scope, null)

  const requestRevision = revision
  const request = chatApi
    .unreadSummary()
    .then((s) => {
      if (revision !== requestRevision || inflightFor !== scope || currentScope() !== scope) return
      const notificationCount = toCount(s?.notificationCount)
      const chatCount = toCount(s?.chatCount)
      const total = toCount(s?.total)
      const listUnreadCount = toCount(s?.listUnreadCount)
      write(scope, {
        notificationCount,
        chatCount,
        total: total > 0 ? total : notificationCount + chatCount,
        listUnreadCount,
      })
    })
    .catch(() => {
      // 同 scope 已有成功值时保留它；首次失败保持 null（未知），绝不写确定 0
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

/** 会话结束/身份切换：清缓存并使在途请求失效，广播未知状态。 */
export function resetUnreadCount() {
  revision += 1
  cached = null
  cachedFor = null
  inflight = null
  inflightFor = null
  emit()
}

/** 轮询周期（ADR-056）：近实时送达的代价上限，先保守 45s；要调快/调慢改这一个数。 */
const POLL_MS = 45_000
let pollTimer: number | null = null
let pollRefs = 0

function onVisibilityChange() {
  // 从后台切回前台立即补一次：隐藏期间不打网络，但回来不能干等下一个周期
  if (document.visibilityState === 'visible') void refreshUnreadCount()
}

/** 会话级单例定时器：第一个订阅者启动、最后一个离开时停（铃铛在共享 Layout 上，天然整会话存活）。 */
function startUnreadPolling() {
  pollRefs += 1
  if (pollTimer !== null) return
  pollTimer = window.setInterval(() => {
    if (document.visibilityState === 'visible') void refreshUnreadCount()
  }, POLL_MS)
  document.addEventListener('visibilitychange', onVisibilityChange)
}

function stopUnreadPolling() {
  pollRefs -= 1
  if (pollRefs > 0 || pollTimer === null) return
  window.clearInterval(pollTimer)
  pollTimer = null
  document.removeEventListener('visibilitychange', onVisibilityChange)
}

/** 铃铛用。`count=null` 表示"还不知道"，调用方据此不渲染角标而不是渲染 0；summary 供分账展示。 */
export function useUnreadCount(): { count: number | null; summary: UnreadSummary | null; reload: () => void } {
  const [value, setValue] = useState<UnreadSummary | null>(() => getUnreadCountSnapshot())
  useEffect(() => {
    const unsubscribe = subscribeUnreadCount(setValue)
    setValue(getUnreadCountSnapshot())
    void refreshUnreadCount()
    // 切店不重新挂载铃铛（它在共享 Layout 上），所以这里订阅上下文变化补一次刷新。
    // scope 同时含用户与门店：换账号/换店都会走到 refresh，先广播未知再发新请求。
    const unsubscribeCtx = storeContext.subscribe(() => {
      setValue(getUnreadCountSnapshot())
      if (currentScope() !== cachedFor) void refreshUnreadCount()
    })
    startUnreadPolling()
    return () => {
      unsubscribe()
      unsubscribeCtx()
      stopUnreadPolling()
    }
  }, [])
  const reload = useCallback(() => {
    void refreshUnreadCount()
  }, [])
  return { count: value?.total ?? null, summary: value, reload }
}

// 所有 token 清除路径（登出、会话失效、改密/重置密码）统一清空身份缓存与在途请求。
tokenStore.onClear(resetUnreadCount)
