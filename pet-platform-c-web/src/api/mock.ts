import { BizCode, BizError, RequestOptions, TokenData } from './types'

/**
 * 模拟接口模式（无后端时预览）。严格镜像后端契约（ADR-009 注册/登录分离）：
 * 相同 path、请求体、响应 data 结构与错误码（20001/20002/20003）。
 * 通过 .env 的 VITE_USE_MOCK=true 启用；调用方代码（api/auth.ts）无需改动。
 */

interface MockUser {
  password: string | null
  userId: string
}

const codes = new Map<string, string>()
const lastSent = new Map<string, number>()
const users = new Map<string, MockUser>()
let seq = 0

// 与后端 local 默认账号对齐（ADR-009 开发期种子）
users.set('13800000000', { password: 'Pet@123456', userId: '2101600000000000001' })

function body<T>(opts: RequestOptions): T {
  return (opts.body ?? {}) as T
}

const delay = (ms: number) => new Promise((r) => setTimeout(r, ms))

function verifyCode(phone: string, code: string): void {
  const expected = codes.get(phone)
  if (!expected || expected !== code) throw new BizError(BizCode.SMS_CODE_INVALID, '验证码错误或已失效')
  codes.delete(phone) // 一次性
}

function issueTokens(phone: string, userId: string): TokenData {
  return {
    accessToken: `mock.access.${btoa(phone)}`,
    refreshToken: `mock.refresh.${btoa(phone)}`,
    accessExpiresIn: 7200,
    userId,
  }
}

export async function mockRequest<T>(path: string, opts: RequestOptions): Promise<T> {
  await delay(320) // 模拟网络往返，便于观察 loading 态

  if (path.endsWith('/public/sms-code')) {
    const { phone } = body<{ phone: string }>(opts)
    if (!/^1\d{10}$/.test(phone ?? '')) {
      throw new BizError(BizCode.PARAM_INVALID, '手机号格式不正确')
    }
    const now = Date.now()
    if (now - (lastSent.get(phone) ?? 0) < 60_000) {
      throw new BizError(BizCode.RATE_LIMITED, '发送过于频繁，请 60 秒后再试')
    }
    lastSent.set(phone, now)
    const code = String(Math.floor(100000 + Math.random() * 900000))
    codes.set(phone, code)
    // 与后端 [DEV-SMS] 日志对应：mock 模式把验证码打到浏览器控制台，便于预览完整流程
    console.info(`[MOCK-SMS] 向 ${phone} 发送验证码 ${code}（5 分钟有效，mock 模式）`)
    return null as T
  }

  if (path.endsWith('/auth/register')) {
    const { phone, code, password } = body<{ phone: string; code: string; password: string }>(opts)
    if (!/^1\d{10}$/.test(phone ?? '')) throw new BizError(BizCode.PARAM_INVALID, '手机号格式不正确')
    if (!/^\d{6}$/.test(code ?? '')) throw new BizError(BizCode.PARAM_INVALID, '请输入 6 位验证码')
    if (!/^(?=.*[A-Za-z])(?=.*\d)\S{8,20}$/.test(password ?? '')) {
      throw new BizError(BizCode.PARAM_INVALID, '密码须为8-20位且包含字母和数字')
    }
    verifyCode(phone, code)
    if (users.has(phone)) throw new BizError(BizCode.PHONE_REGISTERED, '手机号已注册')
    seq += 1
    users.set(phone, { password, userId: `21016${String(600000000 + seq).padStart(13, '0')}` })
    return null as T
  }

  // 注意顺序：/auth/login/password 也 endsWith /login 的前缀场景，必须先判更具体的
  if (path.endsWith('/auth/login/password')) {
    const { phone, password } = body<{ phone: string; password: string }>(opts)
    if (!/^1\d{10}$/.test(phone ?? '')) throw new BizError(BizCode.PARAM_INVALID, '手机号格式不正确')
    const user = users.get(phone)
    if (!user || user.password === null || user.password !== password) {
      throw new BizError(BizCode.PASSWORD_INCORRECT, '手机号或密码错误')
    }
    return issueTokens(phone, user.userId) as T
  }

  if (path.endsWith('/auth/login')) {
    const { phone, code } = body<{ phone: string; code: string }>(opts)
    if (!/^1\d{10}$/.test(phone ?? '')) throw new BizError(BizCode.PARAM_INVALID, '手机号格式不正确')
    if (!/^\d{6}$/.test(code ?? '')) throw new BizError(BizCode.PARAM_INVALID, '请输入 6 位验证码')
    verifyCode(phone, code)
    const user = users.get(phone)
    if (!user) throw new BizError(BizCode.PHONE_NOT_REGISTERED, '手机号未注册，请先注册')
    return issueTokens(phone, user.userId) as T
  }

  if (path.endsWith('/auth/refresh')) {
    const { refreshToken } = body<{ refreshToken: string }>(opts)
    if (!refreshToken) throw new BizError(BizCode.REFRESH_INVALID, 'refresh token 已失效，请重新登录')
    return {
      accessToken: 'mock.access.refreshed',
      refreshToken: 'mock.refresh.rotated',
      accessExpiresIn: 7200,
      userId: '2101600000000000001',
    } as T
  }

  if (path.endsWith('/auth/logout')) {
    return null as T
  }

  throw new BizError(BizCode.NOT_FOUND, `mock 未实现的接口：${path}`)
}
