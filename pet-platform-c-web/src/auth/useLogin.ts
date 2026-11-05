import { useCallback, useEffect, useRef, useState } from 'react'
import { authApi } from '../api/auth'
import { BizCode, BizError } from '../api/types'
import { tokenStore } from './tokenStore'
import { session } from './session'
import { resetUnreadCount } from '../lib/unreadCount'

export type ToastTone = 'info' | 'success' | 'error'

/** 登录方式（ADR-009）：验证码 / 密码 */
export type LoginMode = 'sms' | 'password'

export interface LoginCallbacks {
  /** 登录成功（token 已保存）后回调，用于跳转占位路由 /home */
  onLoggedIn: (info: { userId: string }) => void
  /** 轻量提示（Toast），避免使用浏览器原生 alert */
  onToast: (message: string, tone?: ToastTone) => void
}

const PHONE_RE = /^1\d{10}$/
const CODE_RE = /^\d{6}$/
const PASSWORD_RE = /^(?=.*[A-Za-z])(?=.*\d)\S{8,20}$/
const COUNTDOWN_SECONDS = 60

/**
 * 登录逻辑（与视觉组件分离）：双方式（短信/密码）、表单状态、校验、60s 倒计时、loading、
 * 以及与后端契约一致的错误码分支（11001/20002/20003/11005/10005/网络）。
 * 视觉组件只消费本 hook 返回的 state 与 action。
 */
export function useLogin({ onLoggedIn, onToast }: LoginCallbacks) {
  const [mode, setMode] = useState<LoginMode>('sms')
  const [phone, setPhoneState] = useState('')
  const [code, setCodeState] = useState('')
  const [password, setPasswordState] = useState('')
  const [phoneError, setPhoneError] = useState('')
  const [codeError, setCodeError] = useState('')
  const [passwordError, setPasswordError] = useState('')
  const [sending, setSending] = useState(false)
  const [loggingIn, setLoggingIn] = useState(false)
  const [countdown, setCountdown] = useState(0)
  const timerRef = useRef<number | null>(null)

  const phoneValid = PHONE_RE.test(phone)
  const codeValid = CODE_RE.test(code)
  const passwordValid = PASSWORD_RE.test(password)

  useEffect(() => {
    return () => {
      if (timerRef.current) window.clearInterval(timerRef.current)
    }
  }, [])

  const startCountdown = useCallback(() => {
    setCountdown(COUNTDOWN_SECONDS)
    if (timerRef.current) window.clearInterval(timerRef.current)
    timerRef.current = window.setInterval(() => {
      setCountdown((c) => {
        if (c <= 1) {
          if (timerRef.current) window.clearInterval(timerRef.current)
          return 0
        }
        return c - 1
      })
    }, 1000)
  }, [])

  const setPhone = useCallback((v: string) => {
    setPhoneState(v.replace(/\D/g, '').slice(0, 11))
    setPhoneError('')
  }, [])

  const setCode = useCallback((v: string) => {
    setCodeState(v.replace(/\D/g, '').slice(0, 6))
    setCodeError('')
  }, [])

  const setPassword = useCallback((v: string) => {
    setPasswordState(v)
    setPasswordError('')
  }, [])

  const switchMode = useCallback((m: LoginMode) => {
    setMode(m)
    setCodeError('')
    setPasswordError('')
  }, [])

  const sendCode = useCallback(async () => {
    if (sending || countdown > 0) return
    if (!phoneValid) {
      setPhoneError('请输入正确的 11 位手机号')
      return
    }
    setPhoneError('')
    setSending(true)
    try {
      await authApi.sendSmsCode(phone)
      startCountdown()
      onToast('验证码已发送，请注意查收', 'success')
    } catch (e) {
      if (e instanceof BizError) {
        if (e.code === BizCode.RATE_LIMITED) {
          onToast(e.message || '发送过于频繁，请稍后再试', 'error')
          startCountdown() // 后端已限流，前端同步进入倒计时避免连点
        } else if (e.code === BizCode.PARAM_INVALID) {
          setPhoneError(e.message || '手机号格式不正确')
        } else {
          onToast(e.message || '发送失败，请稍后再试', 'error')
        }
      } else {
        onToast('网络异常，请稍后再试', 'error')
      }
    } finally {
      setSending(false)
    }
  }, [phone, phoneValid, sending, countdown, startCountdown, onToast])

  const submit = useCallback(async () => {
    if (loggingIn) return // 防重复提交
    if (!phoneValid) {
      setPhoneError('请输入正确的 11 位手机号')
      return
    }
    setPhoneError('')
    if (mode === 'sms' && !codeValid) {
      setCodeError('请输入 6 位验证码')
      return
    }
    if (mode === 'password' && !passwordValid) {
      setPasswordError('密码须为 8-20 位且包含字母和数字')
      return
    }

    setLoggingIn(true)
    try {
      const data =
        mode === 'sms' ? await authApi.login(phone, code) : await authApi.loginByPassword(phone, password)
      // 新会话建立：先作废旧会话任何在途 refresh，防其结果覆盖新 token
      session.bump()
      tokenStore.set(data)
      resetUnreadCount() // 登录成功即建立新身份的未读缓存，不能复用上一会话的角标
      onToast('登录成功，欢迎回来', 'success')
      onLoggedIn({ userId: String(data.userId) })
    } catch (e) {
      if (e instanceof BizError) {
        switch (e.code) {
          case BizCode.SMS_CODE_INVALID:
            setCodeError(e.message || '验证码错误或已失效')
            break
          case BizCode.PASSWORD_INCORRECT:
            setPasswordError(e.message || '手机号或密码错误')
            break
          case BizCode.PHONE_NOT_REGISTERED:
            onToast(e.message || '手机号未注册，请先注册', 'error')
            break
          case BizCode.ACCOUNT_DISABLED:
            onToast(e.message || '账号已封禁，请联系客服', 'error')
            break
          case BizCode.RATE_LIMITED:
            onToast(e.message || '操作过于频繁，请稍后再试', 'error')
            break
          case BizCode.UNAUTHORIZED:
          case BizCode.TOKEN_EXPIRED:
            onToast(e.message || '登录已过期，请重新登录', 'error')
            break
          case BizCode.PARAM_INVALID:
            onToast(e.message || '参数校验失败', 'error')
            break
          default:
            onToast(e.message || '登录失败，请稍后再试', 'error')
        }
      } else {
        onToast('网络异常，请稍后再试', 'error')
      }
    } finally {
      setLoggingIn(false)
    }
  }, [mode, phone, code, password, phoneValid, codeValid, passwordValid, loggingIn, onLoggedIn, onToast])

  return {
    // state
    mode,
    phone,
    code,
    password,
    phoneError,
    codeError,
    passwordError,
    sending,
    loggingIn,
    countdown,
    phoneValid,
    codeValid,
    passwordValid,
    canSubmit: phoneValid && (mode === 'sms' ? codeValid : passwordValid) && !loggingIn,
    // actions
    switchMode,
    setPhone,
    setCode,
    setPassword,
    sendCode,
    submit,
  }
}

export type LoginViewModel = ReturnType<typeof useLogin>
