import { useCallback, useEffect, useRef, useState } from 'react'
import { authApi } from '../api/auth'
import { BizCode, BizError } from '../api/types'
import type { ToastTone } from './useLogin'

const PHONE_RE = /^1\d{10}$/
const CODE_RE = /^\d{6}$/
const PASSWORD_RE = /^(?=.*[A-Za-z])(?=.*\d)\S{8,20}$/
const COUNTDOWN_SECONDS = 60

export interface ResetPasswordCallbacks {
  onReset: () => void
  onToast: (message: string, tone?: ToastTone) => void
}

/**
 * 忘记密码（ADR-011）：RESET 场景验证码 + 新密码。
 * 与 useLogin/useRegister 保持同一手机号、验证码、密码规则；验证码成功后才启动倒计时。
 */
export function useResetPassword({ onReset, onToast }: ResetPasswordCallbacks) {
  const [phone, setPhoneState] = useState('')
  const [code, setCodeState] = useState('')
  const [password, setPasswordState] = useState('')
  const [confirm, setConfirmState] = useState('')
  const [phoneError, setPhoneError] = useState('')
  const [codeError, setCodeError] = useState('')
  const [passwordError, setPasswordError] = useState('')
  const [confirmError, setConfirmError] = useState('')
  const [formError, setFormError] = useState('')
  const [sending, setSending] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [countdown, setCountdown] = useState(0)
  const timerRef = useRef<number | null>(null)

  const phoneValid = PHONE_RE.test(phone)
  const codeValid = CODE_RE.test(code)
  const passwordValid = PASSWORD_RE.test(password)
  const confirmValid = confirm.length > 0 && confirm === password

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
    setFormError('')
  }, [])

  const setCode = useCallback((v: string) => {
    setCodeState(v.replace(/\D/g, '').slice(0, 6))
    setCodeError('')
    setFormError('')
  }, [])

  const setPassword = useCallback((v: string) => {
    setPasswordState(v)
    setPasswordError('')
    setFormError('')
  }, [])

  const setConfirm = useCallback((v: string) => {
    setConfirmState(v)
    setConfirmError('')
    setFormError('')
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
      await authApi.sendSmsCode(phone, 'RESET')
      // 只有服务端明确发送成功才启动倒计时；失败不能伪装成已发送。
      startCountdown()
      onToast('验证码已发送，请注意查收', 'success')
    } catch (e) {
      if (e instanceof BizError) {
        if (e.code === BizCode.RATE_LIMITED) {
          onToast(e.message || '发送过于频繁，请稍后再试', 'error')
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
    if (submitting) return
    const phoneOk = PHONE_RE.test(phone)
    const codeOk = CODE_RE.test(code)
    const passwordOk = PASSWORD_RE.test(password)
    const confirmOk = confirm.length > 0 && confirm === password
    setPhoneError(phoneOk ? '' : '请输入正确的 11 位手机号')
    setCodeError(codeOk ? '' : '请输入 6 位验证码')
    setPasswordError(passwordOk ? '' : '密码须为 8-20 位且包含字母和数字')
    setConfirmError(confirmOk ? '' : confirm.length === 0 ? '请再次输入新密码' : '两次输入的新密码不一致')
    setFormError('')
    if (!phoneOk || !codeOk || !passwordOk || !confirmOk) return

    setSubmitting(true)
    try {
      await authApi.resetPassword(phone, code, password)
      onReset()
    } catch (e) {
      if (e instanceof BizError) {
        switch (e.code) {
          case BizCode.SMS_CODE_INVALID:
            setCodeError(e.message || '验证码错误或已失效')
            break
          case BizCode.PHONE_NOT_REGISTERED:
            setPhoneError(e.message || '手机号未注册，请先注册')
            break
          case BizCode.ACCOUNT_DISABLED:
            setFormError(e.message || '账号已禁用，请联系客服')
            break
          case BizCode.RATE_LIMITED:
            setFormError(e.message || '操作过于频繁，请稍后再试')
            break
          case BizCode.PARAM_INVALID:
            setFormError(e.message || '参数校验失败')
            break
          default:
            setFormError(e.message || '密码重置失败，请稍后再试')
        }
      } else {
        setFormError('网络异常，请稍后再试')
      }
    } finally {
      setSubmitting(false)
    }
  }, [phone, code, password, confirm, submitting, onReset])

  return {
    phone,
    code,
    password,
    confirm,
    phoneError,
    codeError,
    passwordError,
    confirmError,
    formError,
    sending,
    submitting,
    countdown,
    phoneValid,
    codeValid,
    passwordValid,
    confirmValid,
    canSubmit: phoneValid && codeValid && passwordValid && confirmValid && !submitting,
    setPhone,
    setCode,
    setPassword,
    setConfirm,
    sendCode,
    submit,
  }
}

export type ResetPasswordViewModel = ReturnType<typeof useResetPassword>
