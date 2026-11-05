import { useCallback, useEffect, useState } from 'react'
import { chatApi } from '../api/chat'
import { tokenStore } from '../auth/tokenStore'

/**
 * 未读数的模块级单例（ADR-041；P3 起数字源切换为 /customer/unread-summary）。
 *
 * 为什么不是每个组件各自 useEffect 拉一次：铃铛会同时/先后出现在主页、订单页、消息中心，
 * 已读操作发生在消息中心而红点在铃铛上——两处必须看到同一个数，否则"点了已读红点不减"。
 * 一处缓存 + 订阅 + **会话级轮询单例**（ADR-056）：挂载即拉，之后每 45s 拉一次（页面隐藏时不打
 * 网络，切回前台立即补一次）；只做轮询，不做长连接/WebSocket/短信。轮询定时器是模块级的——
 * 多个挂载点（铃铛、我的页）共用一个定时器，不会各自 setInterval。
 *
 * P3 口径：后端 summary 的 **total 已是"系统通知未读 + 聊天未读"的真实合计**——
 * 铃铛直接用 total，**不得再加 chatCount**（重复累计）；分账（notificationCount/chatCount）
 * 供消息中心两个分类与「我的」页展示。
 *
 * 身份/竞态：缓存 scope = 当前 token 的 userId；reset/换账号以 revision 使在途请求失效。
 * 请求在 then/catch/finally 都按 revision + scope + 自身 Promise 身份复核：
 * 旧响应不能覆盖新账号，旧 finally 不能清掉新请求的 inflight。
 * 失败保留同一 scope 上次成功值；首次失败保持 null（未知），不伪造 0。
 */

export interface UnreadSummary {
  notificationCount: number
  chatCount: number
  total: number
}

let cached: UnreadSummary | null = null
let cachedFor: string | null = null
let inflight: Promise<void> | null = null
let inflightFor: string | null = null
let revision = 0
const listeners = new Set<(value: UnreadSummary | null) => void>()

function currentScope(): string | null {
  return tokenStore.getUserId()
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

/** 当前身份下最后一次成功值；null 表示未知（首次未成功或已切换身份）。 */
export function getUnreadCountSnapshot(): UnreadSummary | null {
  return readCurrent()
}

/** 订阅当前身份的未读摘要；切换身份后订阅者只会收到新身份的当前值。 */
export function subscribeUnreadCount(listener: (value: UnreadSummary | null) => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** 拉一次未读摘要；同一身份并发调用共用一发。失败保留旧值，不闪回 0。 */
export function refreshUnreadCount(): Promise<void> {
  const scope = currentScope()
  if (!scope) {
    resetUnreadCount()
    return Promise.resolve()
  }
  if (inflight && inflightFor === scope) return inflight
  // 身份变化：先把旧身份的数字作废，避免新页面短暂显示上一个账号的数值
  if (cachedFor !== scope) write(scope, null)

  const requestRevision = revision
  const request = chatApi
    .unreadSummary()
    .then((s) => {
      if (revision !== requestRevision || inflightFor !== scope || currentScope() !== scope) return
      const notificationCount = toCount(s?.notificationCount)
      const chatCount = toCount(s?.chatCount)
      const total = toCount(s?.total)
      write(scope, { notificationCount, chatCount, total: total > 0 ? total : notificationCount + chatCount })
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

/** 会话级单例定时器：第一个订阅者启动、最后一个离开时停（铃铛/我的页共用）。 */
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

/** count=total（铃铛直接用）；null=当前身份尚未成功获取，不可显示成 0；summary 供分账展示。 */
export function useUnreadCount(): { count: number | null; summary: UnreadSummary | null; reload: () => void } {
  const [value, setValue] = useState<UnreadSummary | null>(() => getUnreadCountSnapshot())
  useEffect(() => {
    const unsubscribe = subscribeUnreadCount(setValue)
    setValue(getUnreadCountSnapshot())
    // 挂载即拉一次：铃铛随页面挂载，所以"进主页/从订单详情返回"都会自动刷新（单飞去重）。
    void refreshUnreadCount()
    startUnreadPolling()
    return () => {
      unsubscribe()
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
