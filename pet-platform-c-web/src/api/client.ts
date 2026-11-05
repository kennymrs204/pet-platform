import { ApiEnvelope, BizCode, BizError, RequestOptions } from './types'
import { mockRequest } from './mock'
import { tokenStore } from '../auth/tokenStore'
import { session } from '../auth/session'

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'
const BASE = (import.meta.env.VITE_API_BASE || '/api/v1').replace(/\/$/, '')

/** 给"不套统一信封"的端点（支付回调类）用的裸地址与鉴权头。 */
export const apiBase = BASE
export const authHeader = (): Record<string, string> => {
  const t = tokenStore.getAccess()
  return t ? { Authorization: `Bearer ${t}` } : {}
}

/**
 * 统一请求：无论 HTTP 状态，一律解析后端信封；code!==0 抛 BizError。
 * 契约地址、请求体、响应处理与后端严格一致（真实/mock 同一套调用方代码）。
 *
 * 会话续期（与 B 端 `auth/session.ts` 同一套语义）：仅对 `opts.auth=true` 的业务请求生效——
 * - 10002 且非重试 → single-flight refresh → renewed 则带新 token 重试**一次**；
 *   若本请求在途期间别的请求已续期（当前 access ≠ 发出时那枚且仍有效）→ 直接重试，不重复 refresh；
 * - 重试后仍 10002 → 会话失效收敛（清会话），错误抛回调用方，由 `useBizFail` 提示并回登录；
 * - refresh 认证性失效(11003/11004/11005) → 清会话；网络类失败 → 保留会话原样抛错；
 * - 10003/10004 及其他业务码**绝不**触发 refresh。
 * mock 模式在函数首行就返回，不走真实会话。
 */
async function request<T>(path: string, opts: RequestOptions = {}, isRetry = false): Promise<T> {
  if (USE_MOCK) {
    return mockRequest<T>(path, opts)
  }
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const usedToken = opts.auth ? tokenStore.getAccess() : null
  if (opts.auth && usedToken) headers['Authorization'] = `Bearer ${usedToken}`
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
      const current = tokenStore.getAccess()
      if (current && current !== usedToken) {
        return request<T>(path, opts, true)
      }
      const out = await session.refresh()
      if (out.status === 'renewed') {
        return request<T>(path, opts, true)
      }
      if (out.status === 'invalid') session.expireLocal()
      // network / superseded：不动会话，原错误抛回调用方
    } else {
      session.expireLocal()
    }
  }
  if (env.code !== BizCode.SUCCESS) {
    throw new BizError(env.code, env.message || '请求失败', env.traceId ?? null)
  }
  return env.data
}

export const http = {
  get: <T>(path: string, opts: RequestOptions = {}) => request<T>(path, { ...opts, method: 'GET' }),
  post: <T>(path: string, body?: unknown, opts: RequestOptions = {}) =>
    request<T>(path, { ...opts, method: 'POST', body }),
  put: <T>(path: string, body?: unknown, opts: RequestOptions = {}) =>
    request<T>(path, { ...opts, method: 'PUT', body }),
  delete: <T>(path: string, opts: RequestOptions = {}) => request<T>(path, { ...opts, method: 'DELETE' }),
}
