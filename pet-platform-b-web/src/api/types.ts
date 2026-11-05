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

/** 门店身份归属（ADR-012 GET /auth/employee-identities；Long 已由后端字符串化，前端一律按 string 处理防精度丢失）。 */
export interface StoreIdentity {
  storeId: string
  /** 阶段 1 占位 null，store 段补值；显示层回退「门店 {id}」 */
  storeName: string | null
  isPrimary: boolean
  roles: string[]
}

export interface EmployeeIdentities {
  employeeId: string | null
  name: string | null
  status: string | null
  stores: StoreIdentity[]
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
  NETWORK: -1,
  /** 纯客户端哨兵：门店上下文未初始化时业务请求拒绝发出（不出网、不降级为无头请求）。非后端码。 */
  NO_STORE_CTX: -2,
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
  /**
   * 门店头的**显式来源**，只用于「门店上下文尚未建立时的一次性装配读取」
   * （目前唯一调用方：`adminApi.stores()` 拉平台候选门店，值来自服务端 identities 返回的归属店）。
   *
   * <p>它**不是**放宽校验：store-scoped 请求仍然必须带 X-Store-Id，两者都取不到时照旧抛
   * `NO_STORE_CTX` 拒绝发出；服务端仍按该 ctx 的权限码判权（不对就 10003）。
   * ⚠ 取值**必须来自服务端响应**（identities / 候选集），不得来自 URL、表单、store 或任何用户可编辑的地方，
   * 否则就等于把"选哪个门店上下文"交给调用方——那是本口存在的唯一理由之外的唯一风险。
   */
  store?: string
}
