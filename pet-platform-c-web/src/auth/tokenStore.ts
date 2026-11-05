import { TokenData } from '../api/types'

/**
 * Token 存储（前后端分离 SPA 的现实折中）。
 * - 用 sessionStorage（关闭标签页即清），比 localStorage 降低 XSS 持久化风险；另存内存副本供即时读取。
 * - 后端当前在响应体返回 token（非 httpOnly cookie）。生产强化建议：改由 BFF 下发 httpOnly + Secure + SameSite
 *   cookie 承载 refresh，前端仅持短效 access 于内存。此处以 tokenStore 抽象隔离，便于后续无痛替换。
 */
const K_ACCESS = 'ppc.access'
const K_REFRESH = 'ppc.refresh'
const K_EXPIRES = 'ppc.expiresAt'
const K_USER = 'ppc.userId'

let memAccess: string | null = null
const clearListeners = new Set<() => void>()

function safeGet(key: string): string | null {
  try {
    return window.sessionStorage.getItem(key)
  } catch {
    return null
  }
}
function safeSet(key: string, val: string): void {
  try {
    window.sessionStorage.setItem(key, val)
  } catch {
    /* 隐私模式/存储满：忽略，内存副本仍可用 */
  }
}
function safeRemove(key: string): void {
  try {
    window.sessionStorage.removeItem(key)
  } catch {
    /* ignore */
  }
}

export const tokenStore = {
  set(data: TokenData): void {
    memAccess = data.accessToken
    const ttl = Number(data.accessExpiresIn) || 7200
    safeSet(K_ACCESS, data.accessToken)
    safeSet(K_REFRESH, data.refreshToken)
    safeSet(K_EXPIRES, String(Date.now() + ttl * 1000))
    safeSet(K_USER, String(data.userId))
  },
  getAccess(): string | null {
    // 过期即视为无 token（与 B 端同源修复：原实现两个分支同值返回，过期 token 照发）
    if (tokenStore.isExpired()) return null
    return memAccess ?? safeGet(K_ACCESS)
  },
  getRefresh(): string | null {
    return safeGet(K_REFRESH)
  },
  getUserId(): string | null {
    return safeGet(K_USER)
  },
  isExpired(): boolean {
    const exp = Number(safeGet(K_EXPIRES) ?? 0)
    return exp !== 0 && Date.now() > exp
  },
  isAuthenticated(): boolean {
    return !!tokenStore.getAccess() && !tokenStore.isExpired()
  },
  /** 会话终止的统一观察点：token 清除时联动清空按身份缓存的模块态。 */
  onClear(listener: () => void): () => void {
    clearListeners.add(listener)
    return () => clearListeners.delete(listener)
  },
  clear(): void {
    memAccess = null
    ;[K_ACCESS, K_REFRESH, K_EXPIRES, K_USER].forEach(safeRemove)
    for (const listener of [...clearListeners]) listener()
  },
}
