import { authApi } from '../api/auth'
import { BizCode, BizError, type TokenData } from '../api/types'
import { tokenStore } from './tokenStore'

/**
 * 会话状态机（移植自 B 端 `pet-platform-b-web/src/auth/session.ts`，语义一致，去掉门店上下文部分）。
 *
 * - **single-flight**：并发 10002 共享同一个 refresh Promise。后端 refresh 是一次性轮换，
 *   并发双发必有一发命中 11004 并把两枚都作废，所以全局只允许在途一个；
 * - **世代号 generation**：登录成功/主动登出前 bump()，让一切在途 refresh 结果作废——
 *   既不重建已登出的会话，也不覆盖新登录产生的 token；
 * - **内容守卫**：refresh 回来先比对 refreshToken 是否仍是发起时那一枚。世代号在同模块实例内最快判定，
 *   而 Vite HMR 会让页面里出现模块副本，此时靠 sessionStorage 里的 refreshToken 内容比对兜底；
 * - 四种结局：renewed / invalid（认证性失效→清会话）/ network（保留会话与 refreshToken，下次操作可再续）
 *   / superseded（会话已更替，静默放弃）。
 *
 * 与 B 端的差异：B 端由 Layout 注册 setExpiredHandler 做 Toast + 跳登录；C 端没有共享 Layout，
 * 每个业务页的 catch 都走 `useBizFail`（Toast + 10002 清会话回登录），故此处不再开回调口子。
 * 代价：空闲时后台续期失败不会立刻跳转，要等下一次操作才收敛——C 端可接受。
 * 安全：任何分支不打印 token/验证码/密码。
 */

export type RefreshStatus = 'renewed' | 'invalid' | 'network' | 'superseded'

export interface RefreshResult {
  status: RefreshStatus
  /** invalid 时携带 refresh 调用的业务码（如 11005 封禁），供上层区分文案 */
  code?: number
}

let inflight: Promise<RefreshResult> | null = null
let generation = 0

export const session = {
  /** 新登录成功 / 主动登出前调用：让一切在途 refresh 结果作废 */
  bump(): void {
    generation += 1
  },

  refresh(): Promise<RefreshResult> {
    if (!inflight) {
      const gen = generation
      inflight = doRefresh(gen).finally(() => {
        inflight = null
      })
    }
    return inflight
  },

  /** 认证性失效收敛：清会话 + 作废在途结果。已无会话时幂等跳过 */
  expireLocal(): void {
    if (!tokenStore.getUserId()) return
    tokenStore.clear()
    generation += 1
  },
}

async function doRefresh(gen: number): Promise<RefreshResult> {
  const rt = tokenStore.getRefresh()
  if (!rt) return { status: 'invalid', code: BizCode.UNAUTHORIZED }
  try {
    const data: TokenData = await authApi.refresh(rt)
    if (gen !== generation || tokenStore.getRefresh() !== rt) return { status: 'superseded' }
    if (!data?.accessToken) return { status: 'invalid', code: BizCode.UNAUTHORIZED }
    tokenStore.set(data)
    return { status: 'renewed' }
  } catch (err) {
    if (err instanceof BizError) {
      // 网络/网关类（client 归一为 -1）：保留会话与 refreshToken，下次操作仍可续期
      if (err.code === BizCode.NETWORK) return { status: 'network' }
      // 11003/11004/11005 等认证性失败：会话作废
      return { status: 'invalid', code: err.code }
    }
    return { status: 'network' }
  }
}
