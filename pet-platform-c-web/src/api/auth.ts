import { http } from './client'
import { TokenData } from './types'

/**
 * 认证接口（ADR-009：注册/登录分离；ADR-011：验证码场景隔离）。地址/请求体与后端契约一一对应：
 *   POST /api/v1/public/sms-code   { phone, scene? }  scene=LOGIN(缺省)/REGISTER/RESET
 *   POST /api/v1/auth/register     { phone, code, password }（消费 REGISTER 场景码）
 *   POST /api/v1/auth/login        { phone, code }（验证码登录，消费 LOGIN 场景码，不再自动建号）
 *   POST /api/v1/auth/login/password { phone, password }
 *   POST /api/v1/auth/refresh      { refreshToken }
 *   POST /api/v1/auth/logout       （需 Bearer）
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
  /** ADR-011 忘记密码：RESET 场景码 + 新密码；成功不签发 token，后端清空 refresh 白名单。 */
  resetPassword: (phone: string, code: string, newPassword: string) =>
    http.post<void>('/auth/password/reset', { phone, code, newPassword }),
  refresh: (refreshToken: string) => http.post<TokenData>('/auth/refresh', { refreshToken }),
  logout: () => http.post<void>('/auth/logout', undefined, { auth: true }),
  /** ADR-011：登录态改密，旧密码错 → 20004；成功后后端清空全部 refresh 白名单。 */
  changePassword: (oldPassword: string, newPassword: string) =>
    http.post<void>('/auth/password/change', { oldPassword, newPassword }, { auth: true }),
}
