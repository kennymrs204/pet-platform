/** 后端统一响应信封（第八阶段 §2-2）：HTTP 恒 200，业务成败看 code。 */
export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
  traceId: string | null
}

/**
 * 登录成功返回体。注意：后端 Jackson 将 Long 序列化为字符串，
 * 故 accessExpiresIn / userId 可能是字符串；类型放宽为 number | string，UI 侧统一 String()/Number() 处理。
 */
export interface TokenData {
  accessToken: string
  refreshToken: string
  accessExpiresIn: number | string
  userId: string | number
}

/** 业务错误码常量，严格对齐后端 CommonErrorCode + UserErrorCode，禁止发明段外码。 */
export const BizCode = {
  SUCCESS: 0,
  PARAM_INVALID: 10001,
  UNAUTHORIZED: 10002,
  FORBIDDEN: 10003,
  NOT_FOUND: 10004,
  RATE_LIMITED: 10005,
  ILLEGAL_STATE_TRANSITION: 10006,
  SMS_CODE_INVALID: 11001,
  TOKEN_EXPIRED: 11002,
  TOKEN_INVALID: 11003,
  REFRESH_INVALID: 11004,
  ACCOUNT_DISABLED: 11005,
  PHONE_REGISTERED: 20001,
  PASSWORD_INCORRECT: 20002,
  PHONE_NOT_REGISTERED: 20003,
  /** 第八阶段 §2-2 错误码分段：以下业务段为 C 端页面会命中的码（只登记用到的，不整段抄录）。 */
  PET_NOT_MANAGEABLE: 20101,
  PET_NOT_BOOKABLE: 20102,
  STORE_CLOSED: 30001,
  SERVICE_OFF_SHELF: 31001,
  ORDER_NOT_PAYABLE: 60001,
  NETWORK: -1,
} as const

/** 业务/网络错误：由 http 客户端在 code!==0 或 fetch 失败时抛出，UI 据 code 分支处理。 */
export class BizError extends Error {
  readonly code: number
  readonly traceId: string | null
  constructor(code: number, message: string, traceId: string | null = null) {
    super(message)
    this.name = 'BizError'
    this.code = code
    this.traceId = traceId
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  /** 是否携带 Authorization: Bearer <accessToken> */
  auth?: boolean
  signal?: AbortSignal
}
