import { http } from './client'
import { TokenData, type EmployeeIdentities } from './types'

/**
 * B 端认证接口。与 C 端共用同一套后端 auth 契约（ADR-009 注册/登录分离；ADR-011 验证码场景隔离）：
 *   POST /public/sms-code {phone, scene?} | /auth/register | /auth/login | /auth/login/password
 *   | /auth/refresh | /auth/logout
 * 差异仅在入口与体验：B 端面向门店员工/平台管理员，注册后需管理员绑定员工身份方可使用业务功能。
 */
export type SmsScene = 'LOGIN' | 'REGISTER' | 'RESET'

export const authApi = {
  sendSmsCode: (phone: string, scene?: SmsScene) =>
    http.post<void>('/public/sms-code', scene ? { phone, scene } : { phone }),
  register: (phone: string, code: string, password: string) =>
    http.post<void>('/auth/register', { phone, code, password }),
  login: (phone: string, code: string) => http.post<TokenData>('/auth/login', { phone, code }),
  loginByPassword: (phone: string, password: string) =>
    http.post<TokenData>('/auth/login/password', { phone, password }),
  /**
   * 公开端点：不带 Authorization（旧 access 无意义且可能已过期），
   * 也不走 client 的 10002 拦截（拦截仅对 opts.auth=true 的请求生效，杜绝递归刷新）。
   */
  refresh: (refreshToken: string) => http.post<TokenData>('/auth/refresh', { refreshToken }),
  logout: () => http.post<void>('/auth/logout', undefined, { auth: true }),
  /** 员工身份+ACTIVE 门店归属（ADR-012）。仅需登录态；不带 X-Store-Id（client 对 /auth/ 豁免）。 */
  employeeIdentities: () => http.get<EmployeeIdentities>('/auth/employee-identities', { auth: true }),
  /** 登录态改密（ADR-011）。旧密错 → 20004；规则不合规 → 10001。走 /auth/ 前缀豁免，不带门店头。 */
  changePassword: (oldPassword: string, newPassword: string) =>
    http.post<void>('/auth/password/change', { oldPassword, newPassword }, { auth: true }),
}
