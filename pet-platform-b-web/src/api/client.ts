import { ApiEnvelope, BizCode, BizError, RequestOptions } from './types'
import { tokenStore } from '../auth/tokenStore'
import { session } from '../auth/session'
import { storeContext } from '../auth/storeContext'

const BASE = (import.meta.env.VITE_API_BASE || '/api/v1').replace(/\/$/, '')

/** auth/public 组免门店头（登录/注册/refresh/logout/employee-identities 均在豁免内）；其余 auth 请求=门店业务请求。 */
function isStoreScoped(path: string): boolean {
  const p = path.split('?')[0]
  return !p.startsWith('/auth/') && !p.startsWith('/public/')
}

/**
 * 统一请求：HTTP 恒解析后端信封，code!==0 抛 BizError。
 * 与 C 端同一契约（第八阶段 §2-2），但为独立应用、独立会话（tokenStore 互不共享）。
 *
 * 会话拦截（阶段 2 / 阶段 0 D 表）：仅对 opts.auth=true 的业务请求生效——
 * - 10002 且非重试 → single-flight refresh → 成功则带新 token 重试一次；
 * - 重试后仍 10002 → 会话失效收敛（清会话 + Layout 跳登录），不再 refresh；
 * - refresh 认证性失效(11003/11004/11005) → 清会话回登录；网络类失败 → 保留会话原样抛错；
 * - 10003/10004 及其他业务码绝不触发 refresh。
 */
async function request<T>(path: string, opts: RequestOptions = {}, isRetry = false): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const usedToken = opts.auth ? tokenStore.getAccess() : null
  if (opts.auth && usedToken) headers['Authorization'] = `Bearer ${usedToken}`
  // 门店业务请求：注入当前 X-Store-Id；上下文未就绪则拒绝发出（不降级为无头/旧头请求）。
  // 每次进入都重新取值 → refresh 递归重试自然使用「当前」门店上下文。
  // opts.store 是**显式来源**，只给"上下文尚未建立时的一次性装配读取"用（唯一调用方见 RequestOptions 注释），
  // 它的存在不改变这条不变量：store-scoped 请求必须有门店头，否则照样不发。
  if (opts.auth && isStoreScoped(path)) {
    const storeId = opts.store ?? storeContext.currentStoreId()
    if (!storeId) {
      throw new BizError(BizCode.NO_STORE_CTX, '门店上下文未就绪，无法确定工作门店')
    }
    headers['X-Store-Id'] = storeId
  }
  let res: Response
  try {
    res = await fetch(`${BASE}${path}`, {
      method: opts.method || 'GET',
      headers,
      body: opts.body != null ? JSON.stringify(opts.body) : undefined,
      signal: opts.signal,
    })
  } catch {
    throw new BizError(BizCode.NETWORK, '网络异常，请检查网络连接后重试')
  }
  let env: ApiEnvelope<T>
  try {
    env = (await res.json()) as ApiEnvelope<T>
  } catch {
    throw new BizError(BizCode.NETWORK, `服务异常（HTTP ${res.status}），请稍后重试`)
  }
  if (env.code === BizCode.UNAUTHORIZED && opts.auth) {
    if (!isRetry) {
      // 本请求在途期间已被别的请求续期（当前 access ≠ 发出时用的且有效）→ 直接重试，不重复 refresh
      const current = tokenStore.getAccess()
      if (current && current !== usedToken) {
        return request<T>(path, opts, true)
      }
      const out = await session.refresh()
      if (out.status === 'renewed') {
        return request<T>(path, opts, true)
      }
      if (out.status === 'invalid') {
        session.expireLocal(out.code ?? env.code)
      }
      // network / superseded：不动会话，原错误抛回调用方
    } else {
      // D7：新 token 仍被判 10002 → 会话失效，不再 refresh
      session.expireLocal(env.code)
    }
  }
  if (env.code !== BizCode.SUCCESS) {
    throw new BizError(env.code, env.message || '请求失败', env.traceId ?? null)
  }
  return env.data
}

/**
 * Blob 下载（导出对账单 P5）：响应是 CSV 原文 → blob；响应仍是 JSON 信封 → 按 request() 同口径处理。
 * 会话拦截与 request() 完全一致：10002 → single-flight refresh → 重试一次；重试仍 10002 → 会话失效收敛。
 * 门店头注入规则与 get/post 一致（auth=true 且 store-scoped 必须有 X-Store-Id，否则不发）。
 */
async function download(path: string, opts: RequestOptions = {}, isRetry = false): Promise<Blob> {
  const headers: Record<string, string> = {}
  const usedToken = opts.auth ? tokenStore.getAccess() : null
  if (opts.auth && usedToken) headers['Authorization'] = `Bearer ${usedToken}`
  if (opts.auth && isStoreScoped(path)) {
    const storeId = opts.store ?? storeContext.currentStoreId()
    if (!storeId) {
      throw new BizError(BizCode.NO_STORE_CTX, '门店上下文未就绪，无法确定工作门店')
    }
    headers['X-Store-Id'] = storeId
  }
  let res: Response
  try {
    res = await fetch(`${BASE}${path}`, { method: opts.method || 'GET', headers, signal: opts.signal })
  } catch {
    throw new BizError(BizCode.NETWORK, '网络异常，请检查网络连接后重试')
  }
  const contentType = res.headers.get('Content-Type') || ''
  if (!contentType.includes('application/json')) {
    if (!res.ok) {
      throw new BizError(BizCode.NETWORK, `服务异常（HTTP ${res.status}），请稍后重试`)
    }
    return res.blob()
  }
  let env: ApiEnvelope<never>
  try {
    env = (await res.json()) as ApiEnvelope<never>
  } catch {
    throw new BizError(BizCode.NETWORK, `服务异常（HTTP ${res.status}），请稍后重试`)
  }
  if (env.code === BizCode.UNAUTHORIZED && opts.auth) {
    if (!isRetry) {
      const current = tokenStore.getAccess()
      if (current && current !== usedToken) {
        return download(path, opts, true)
      }
      const out = await session.refresh()
      if (out.status === 'renewed') {
        return download(path, opts, true)
      }
      if (out.status === 'invalid') {
        session.expireLocal(out.code ?? env.code)
      }
    } else {
      session.expireLocal(env.code)
    }
  }
  throw new BizError(env.code, env.message || '请求失败', env.traceId ?? null)
}

export const http = {
  get: <T>(path: string, opts: RequestOptions = {}) => request<T>(path, { ...opts, method: 'GET' }),
  post: <T>(path: string, body?: unknown, opts: RequestOptions = {}) =>
    request<T>(path, { ...opts, method: 'POST', body }),
  /** 设置页/价目写侧用（P8 的更新一律 PUT）；请求体同样走 JSON，门店头注入规则与 get/post 一致。 */
  put: <T>(path: string, body?: unknown, opts: RequestOptions = {}) =>
    request<T>(path, { ...opts, method: 'PUT', body }),
  /** 无请求体的删除（P8 :221 `DELETE /schedules/{id}`，ADR-034）——本客户端首个 DELETE 方法，门店头注入规则同上。 */
  delete: <T>(path: string, opts: RequestOptions = {}) => request<T>(path, { ...opts, method: 'DELETE' }),
  /** 文件下载（导出对账单 P5）：成功=原文 blob，失败=仍解析 JSON 信封抛 BizError；门店头/续期规则同上。 */
  download: (path: string, opts: RequestOptions = {}) => download(path, { ...opts, method: 'GET' }),
}
