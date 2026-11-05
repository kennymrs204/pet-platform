import { authApi } from '../api/auth'
import { BizCode, BizError, type TokenData } from '../api/types'
import { tokenStore } from './tokenStore'

/**
 * 会话状态机（阶段 2 / 阶段 0 D 表）：
 * - single-flight：并发 10002 共享同一个 refresh Promise——后端 refresh 一次性轮换，
 *   并发双发必有一发命中 11004 互相作废，故全局只允许在途一个；
 * - 世代号（generation）：登录/登出 bump()，使在途 refresh 的结果作废——
 *   既不重建已登出的会话，也不覆盖新登录产生的 token；
 * - 四种结局：renewed（写回新 token）/ invalid（认证性失效→清会话回登录）/
 *   network（保留会话交回调用方，下次操作可再续）/ superseded（会话已更替，静默放弃）。
 * 安全：任何分支不 log token/验证码/密码；提示走 Toast，不显示后端内部信息。
 */

export type RefreshStatus = 'renewed' | 'invalid' | 'network' | 'superseded'

export interface RefreshResult {
  status: RefreshStatus
  /** invalid 时携带 refresh 调用的业务码（如 11005），供清会话提示区分文案 */
  code?: number
}

let inflight: Promise<RefreshResult> | null = null
let generation = 0
let expiredHandler: ((reason: 'expired' | 'disabled') => void) | null = null

export const session = {
  /** 新登录成功 / 主动登出前调用：让一切在途 refresh 结果作废 */
  bump(): void {
    generation += 1
  },

  /** Layout 注册会话失效回调（Toast + 跳登录）；卸载时置 null */
  setExpiredHandler(fn: ((reason: 'expired' | 'disabled') => void) | null): void {
    expiredHandler = fn
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

  /** 认证性失效收敛：清会话 + 通知页面层。已无会话时幂等跳过（不重复提示/跳转） */
  expireLocal(code: number): void {
    if (!tokenStore.getUserId()) return
    tokenStore.clear()
    generation += 1
    expiredHandler?.(code === BizCode.ACCOUNT_DISABLED ? 'disabled' : 'expired')
  },
}

async function doRefresh(gen: number): Promise<RefreshResult> {
  const rt = tokenStore.getRefresh()
  if (!rt) return { status: 'invalid', code: BizCode.UNAUTHORIZED }
  try {
    const data: TokenData = await authApi.refresh(rt)
    // 会话在等待期间被登出/重登：世代号变（同图最快判定）或 refreshToken 已非发起时那枚
    // （内容守卫，跨模块实例经共享 sessionStorage 亦成立）→ 丢弃本次结果，绝不写回覆盖新会话
    if (gen !== generation || tokenStore.getRefresh() !== rt) return { status: 'superseded' }
    if (!data?.accessToken) return { status: 'invalid', code: BizCode.UNAUTHORIZED }
    tokenStore.set(data)
    return { status: 'renewed' }
  } catch (err) {
    if (err instanceof BizError) {
      // 网络/网关类（client 归一为 -1）：保留会话与 refreshToken，下次操作仍可续期
      if (err.code === BizCode.NETWORK) return { status: 'network' }
      // 11003/11004/11005 及其他认证性失败：清会话回登录
      return { status: 'invalid', code: err.code }
    }
    return { status: 'network' }
  }
}
