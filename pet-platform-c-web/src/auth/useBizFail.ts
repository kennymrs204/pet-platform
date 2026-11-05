import { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { BizCode, BizError } from '../api/types'
import { session } from './session'
import { useToast } from '../components/ui/Toast'

/** 后端信封 message 缺省时的人话兜底（仅按段给通用文案，不逐码硬编码业务语义）。 */
const GENERIC: Record<number, string> = {
  [BizCode.UNAUTHORIZED]: '登录已过期，请重新登录',
  [BizCode.FORBIDDEN]: '当前账号没有该操作的权限',
  [BizCode.NETWORK]: '网络异常，请稍后重试',
}

export function errText(e: unknown): string {
  if (e instanceof BizError) return e.message || GENERIC[e.code] || '操作失败，请稍后重试'
  if (e instanceof Error) return e.message || '操作失败，请稍后重试'
  return '操作失败，请稍后重试'
}

/**
 * 业务失败统一处理：Toast 提示 + 10002 回登录页。
 * 10002 能抛到这里，说明 client 的自动续期已经失败（refresh 认证性失效，或换新 token 重试后仍被判未登录），
 * 所以这里只做收敛：清会话 + 跳登录，不再尝试 refresh。
 */
export function useBizFail() {
  const { push } = useToast()
  const navigate = useNavigate()
  // 必须 memo：调用方会把它写进 useEffect 依赖，引用每轮变化会造成请求死循环
  return useCallback(
    (e: unknown) => {
      push(errText(e), 'error')
      if (e instanceof BizError && e.code === BizCode.UNAUTHORIZED) {
        // 走到这里说明 client 的续期已失败（refresh 认证性失效或重试仍 10002）
        session.expireLocal()
        navigate('/login', { replace: true })
      }
    },
    [push, navigate],
  )
}
