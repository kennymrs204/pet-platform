import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { authApi } from '../../api/auth'
import { BizCode, BizError } from '../../api/types'
import { tokenStore } from '../../auth/tokenStore'
import { session } from '../../auth/session'
import { storeContext } from '../../auth/storeContext'
import { resetUnreadCount } from '../../lib/unreadCount'
import { cx } from '../../lib/cx'
import Logo from '../ui/Logo'
import { useToast } from '../ui/Toast'
import {
  IconBath,
  IconChat,
  IconDog,
  IconEye,
  IconEyeOff,
  IconHeart,
  IconHouse,
  IconLock,
  IconMsg,
  IconShield,
  IconUser,
} from '../ui/icons'

type Tab = 'login' | 'register'
type LoginMode = 'password' | 'sms'

const PHONE_RE = /^1\d{10}$/
const PWD_RE = /^(?=.*[A-Za-z])(?=.*\d)\S{8,20}$/
const K_REMEMBER = 'ppb.rememberPhone'

const FEATURES = [
  { label: '宠物托管', sub: '安全舒适', Icon: IconHouse, bg: '#E3EDFF', fg: '#2E6BFF' },
  { label: '遛狗服务', sub: '专业可靠', Icon: IconDog, bg: '#E0F5E9', fg: '#2FA96B' },
  { label: '宠物照顾', sub: '贴心细致', Icon: IconHeart, bg: '#FFE9EE', fg: '#E8506B' },
  { label: '宠物洗澡', sub: '专业护理', Icon: IconBath, bg: '#E0F2FE', fg: '#38A8E8' },
]

/** B 端登录/注册（桌面优先，样式对齐设计稿 B-login.png）。与 C 端为独立应用、独立会话。 */
export default function LoginPage() {
  const [tab, setTab] = useState<Tab>('login')
  return (
    <main className="grid min-h-[100dvh] lg:grid-cols-[1.12fr_1fr]">
      <BrandPanel />
      <section className="flex items-center justify-center bg-white px-4 py-10 lg:bg-blue-mist lg:px-8">
        <div className="animate-fade-up w-full max-w-[440px] rounded-3xl bg-white p-7 shadow-card ring-1 ring-line sm:p-9">
          <div className="flex rounded-xl bg-blue-soft p-1" role="tablist" aria-label="登录或注册">
            <TabButton active={tab === 'login'} onClick={() => setTab('login')}>
              登录
            </TabButton>
            <TabButton active={tab === 'register'} onClick={() => setTab('register')}>
              注册
            </TabButton>
          </div>
          {tab === 'login' ? <LoginForm key="login" /> : <RegisterForm key="register" />}
        </div>
      </section>
    </main>
  )
}

function TabButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      role="tab"
      aria-selected={active}
      className={cx(
        'h-10 flex-1 rounded-xl text-sm font-bold transition',
        active ? 'bg-white text-blue shadow-soft' : 'text-muted hover:text-ink',
      )}
    >
      {children}
    </button>
  )
}

/** 左品牌栏（<lg 隐藏，移动窄屏只留卡片，与 C 端同策略）。布局对齐设计稿图二。 */
function BrandPanel() {
  return (
    <aside className="relative hidden overflow-hidden bg-brand-panel lg:sticky lg:top-0 lg:flex lg:h-[100dvh] lg:flex-col lg:px-14 lg:py-10">
      {/* 右上淡爪印装饰 */}
      <svg
        viewBox="0 0 48 48"
        aria-hidden="true"
        className="absolute right-10 top-28 h-40 w-40 rotate-12 text-[#D9E6FF]"
        fill="currentColor"
      >
        <ellipse cx="24" cy="30" rx="7.6" ry="6.4" />
        <ellipse cx="14.2" cy="20.6" rx="3.3" ry="4.2" />
        <ellipse cx="20.6" cy="15.7" rx="3.15" ry="4.3" />
        <ellipse cx="27.4" cy="15.7" rx="3.15" ry="4.3" />
        <ellipse cx="33.8" cy="20.6" rx="3.3" ry="4.2" />
      </svg>

      <div className="relative z-10 flex items-center gap-3">
        <Logo className="h-10 w-10" />
        <div>
          <p className="text-lg font-bold text-ink">宠物管家</p>
          <p className="text-xs text-muted">专业的宠物服务管理平台</p>
        </div>
      </div>

      <div className="relative z-10 my-auto max-w-[560px] pb-6">
        <h1 className="text-[44px] font-black leading-[1.35] text-ink">
          让每一只宠物
          <br />
          都被温柔以待
        </h1>
        <p className="mt-5 text-base leading-relaxed text-muted">
          为宠物提供托管、遛狗、照顾、洗澡等一站式服务
          <br />
          让宠物生活更健康，主人更安心。
        </p>
        <div className="mt-14 flex gap-12">
          {FEATURES.map(({ label, sub, Icon, bg, fg }) => (
            <div key={label} className="flex flex-col items-center text-center">
              <span className="grid h-16 w-16 place-items-center rounded-full shadow-soft" style={{ background: bg }}>
                <Icon className="h-8 w-8" style={{ color: fg }} />
              </span>
              <p className="mt-3 text-sm font-bold text-ink">{label}</p>
              <p className="mt-0.5 text-xs text-muted">{sub}</p>
            </div>
          ))}
        </div>
      </div>
    </aside>
  )
}

function Field({
  label,
  icon,
  error,
  children,
  extra,
}: {
  label: string
  icon: React.ReactNode
  error?: string
  children: React.ReactNode
  extra?: React.ReactNode
}) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-[13px] font-semibold text-ink">{label}</span>
      <span
        className={cx(
          'flex h-12 items-center gap-2.5 rounded-xl border bg-white px-3.5 transition focus-within:ring-4',
          error ? 'border-rose-300 focus-within:ring-rose-100' : 'border-line focus-within:border-blue focus-within:ring-blue/10',
        )}
      >
        <span className="shrink-0 text-muted">{icon}</span>
        {children}
        {extra}
      </span>
      {error && <span className="mt-1 block text-xs text-rose-500">{error}</span>}
    </label>
  )
}

const inputCls = 'w-full bg-transparent text-sm text-ink outline-none placeholder:text-muted/60'

function useSmsCountdown() {
  const [sec, setSec] = useState(0)
  const timer = useRef<number>(0)
  useEffect(() => () => window.clearInterval(timer.current), [])
  const start = () => {
    setSec(60)
    window.clearInterval(timer.current)
    timer.current = window.setInterval(() => {
      setSec((s) => {
        if (s <= 1) window.clearInterval(timer.current)
        return s - 1
      })
    }, 1000)
  }
  return { sec, start }
}

function LoginForm() {
  const navigate = useNavigate()
  const { push } = useToast()
  const [mode, setMode] = useState<LoginMode>('password')
  const [phone, setPhone] = useState(() => window.localStorage.getItem(K_REMEMBER) ?? '')
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [showPwd, setShowPwd] = useState(false)
  const [remember, setRemember] = useState(() => !!window.localStorage.getItem(K_REMEMBER))
  const [busy, setBusy] = useState(false)
  const [errs, setErrs] = useState<{ phone?: string; password?: string; code?: string }>({})
  const { sec, start } = useSmsCountdown()

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const next: typeof errs = {}
    if (!PHONE_RE.test(phone)) next.phone = '请输入 11 位手机号'
    if (mode === 'password' && !password) next.password = '请输入密码'
    if (mode === 'sms' && !/^\d{6}$/.test(code)) next.code = '请输入 6 位验证码'
    setErrs(next)
    if (Object.keys(next).length) return

    setBusy(true)
    try {
      const data = mode === 'password' ? await authApi.loginByPassword(phone, password) : await authApi.login(phone, code)
      session.bump() // 新会话建立：作废旧会话任何在途 refresh，防其结果覆盖新 token
      storeContext.reset() // 清上一账号的门店上下文/身份缓存，避免换账号瞬间闪现旧数据
      tokenStore.set(data)
      resetUnreadCount() // 新身份建立：旧账号的未读数不能带进来
      if (remember) window.localStorage.setItem(K_REMEMBER, phone)
      else window.localStorage.removeItem(K_REMEMBER)
      push('登录成功', 'success')
      navigate('/home', { replace: true })
    } catch (err) {
      if (err instanceof BizError) {
        // 后端契约：密码登录「未注册/密码错」同为 20002（防枚举）；20003 仅短信登录返回
        if (err.code === BizCode.PASSWORD_INCORRECT) setErrs({ password: '手机号或密码错误' })
        else if (err.code === BizCode.PHONE_NOT_REGISTERED) push('手机号未注册，请先注册', 'error')
        else if (err.code === BizCode.SMS_CODE_INVALID) setErrs({ code: '验证码错误或已失效' })
        else if (err.code === BizCode.ACCOUNT_DISABLED) push('账号已被停用，请联系管理员', 'error')
        else if (err.code === BizCode.RATE_LIMITED) push('操作过于频繁，请稍后再试', 'error')
        else push(err.message || '登录失败，请稍后再试', 'error')
      } else {
        push('网络异常，请稍后重试', 'error') // 非 BizError 兜底（原实现静默吞掉）
      }
    } finally {
      setBusy(false)
    }
  }

  const sendCode = async () => {
    if (!PHONE_RE.test(phone)) {
      setErrs({ phone: '请输入 11 位手机号' })
      return
    }
    try {
      await authApi.sendSmsCode(phone)
      start()
      push('验证码已发送（开发环境见后端日志）', 'success')
    } catch (err) {
      push(err instanceof BizError ? err.message : '发送失败，请稍后再试', 'error')
    }
  }

  return (
    <form onSubmit={(e) => void submit(e)} className="mt-6" noValidate>
      <h2 className="text-xl font-bold text-ink">欢迎回来</h2>
      <p className="mt-1 text-[13px] text-muted">登录您的账号，开启宠物服务管理之旅</p>

      <div className="mt-5 space-y-4">
        <Field label="手机号" icon={<IconUser className="h-[18px] w-[18px]" />} error={errs.phone}>
          <input
            value={phone}
            onChange={(e) => setPhone(e.target.value.replace(/\D/g, '').slice(0, 11))}
            placeholder="请输入手机号"
            inputMode="numeric"
            autoComplete="username"
            className={inputCls}
          />
        </Field>

        {mode === 'password' ? (
          <Field
            label="密码"
            icon={<IconLock className="h-[18px] w-[18px]" />}
            error={errs.password}
            extra={
              <button
                type="button"
                aria-label={showPwd ? '隐藏密码' : '显示密码'}
                onClick={() => setShowPwd((v) => !v)}
                className="shrink-0 text-muted hover:text-ink"
              >
                {showPwd ? <IconEyeOff className="h-[18px] w-[18px]" /> : <IconEye className="h-[18px] w-[18px]" />}
              </button>
            }
          >
            <input
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              type={showPwd ? 'text' : 'password'}
              placeholder="请输入密码"
              autoComplete="current-password"
              className={inputCls}
            />
          </Field>
        ) : (
          <Field
            label="验证码"
            icon={<IconMsg className="h-[18px] w-[18px]" />}
            error={errs.code}
            extra={
              <button
                type="button"
                onClick={() => void sendCode()}
                disabled={sec > 0}
                className={cx(
                  'shrink-0 rounded-lg px-3 py-1.5 text-xs font-semibold transition',
                  sec > 0 ? 'bg-blue-soft text-muted' : 'bg-blue text-white hover:bg-blue-deep',
                )}
              >
                {sec > 0 ? `${sec}s 后重发` : '获取验证码'}
              </button>
            }
          >
            <input
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
              placeholder="6 位短信验证码"
              inputMode="numeric"
              className={inputCls}
            />
          </Field>
        )}

        <div className="flex items-center justify-between text-[13px]">
          <label className="flex cursor-pointer items-center gap-1.5 text-muted">
            <input
              type="checkbox"
              checked={remember}
              onChange={(e) => setRemember(e.target.checked)}
              className="h-4 w-4 accent-[#2E6BFF]"
            />
            记住账号
          </label>
          <button
            type="button"
            onClick={() => push('忘记密码：请联系您的店长或平台管理员重置密码', 'info')}
            className="font-semibold text-blue hover:text-blue-deep"
          >
            忘记密码？
          </button>
        </div>

        <button
          type="submit"
          disabled={busy}
          className="h-12 w-full rounded-xl bg-blue text-[15px] font-bold text-white shadow-soft transition hover:bg-blue-deep active:scale-[0.99] disabled:opacity-60"
        >
          {busy ? '登录中…' : '登 录'}
        </button>

        <div className="flex items-center gap-3 text-[11px] text-muted">
          <span className="h-px flex-1 bg-line" />
          其他登录方式
          <span className="h-px flex-1 bg-line" />
        </div>
        <div className="flex justify-center">
          <button
            type="button"
            aria-label="第三方登录"
            onClick={() => push('第三方登录暂未开放，请使用手机号登录', 'info')}
            className="grid h-10 w-10 place-items-center rounded-full border border-line text-[#07C160] transition hover:bg-blue-mist"
          >
            <IconChat className="h-5 w-5" />
          </button>
        </div>
        <p className="pb-1 text-center text-[11px] text-muted">
          <button type="button" onClick={() => setMode(mode === 'password' ? 'sms' : 'password')} className="font-semibold text-blue">
            {mode === 'password' ? '使用验证码登录' : '使用密码登录'}
          </button>
        </p>
      </div>
    </form>
  )
}

function RegisterForm() {
  const { push } = useToast()
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [agree, setAgree] = useState(false)
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)
  const [errs, setErrs] = useState<{ phone?: string; code?: string; password?: string; confirm?: string }>({})
  const { sec, start } = useSmsCountdown()

  const sendCode = async () => {
    if (!PHONE_RE.test(phone)) {
      setErrs((x) => ({ ...x, phone: '请输入 11 位手机号' }))
      return
    }
    try {
      await authApi.sendSmsCode(phone, 'REGISTER')
      start()
      push('验证码已发送（开发环境见后端日志）', 'success')
    } catch (err) {
      push(err instanceof BizError ? err.message : '发送失败，请稍后再试', 'error')
    }
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const next: typeof errs = {}
    if (!PHONE_RE.test(phone)) next.phone = '请输入 11 位手机号'
    if (!/^\d{6}$/.test(code)) next.code = '请输入 6 位验证码'
    if (!PWD_RE.test(password)) next.password = '密码须为 8-20 位且包含字母和数字'
    if (confirm !== password) next.confirm = '两次输入的密码不一致'
    if (!agree) {
      push('请先阅读并同意《用户协议》与《隐私政策》', 'error')
      return
    }
    setErrs(next)
    if (Object.keys(next).length) return

    setBusy(true)
    try {
      await authApi.register(phone, code, password)
      setDone(true)
      push('账号创建成功', 'success')
    } catch (err) {
      if (err instanceof BizError) {
        if (err.code === BizCode.PHONE_REGISTERED) setErrs({ phone: '该手机号已注册，请直接登录' })
        else if (err.code === BizCode.SMS_CODE_INVALID) setErrs({ code: '验证码错误或已失效' })
        else if (err.code === BizCode.RATE_LIMITED) push('操作过于频繁，请稍后再试', 'error')
        else push(err.message || '注册失败，请稍后再试', 'error')
      } else {
        push('网络异常，请稍后重试', 'error')
      }
    } finally {
      setBusy(false)
    }
  }

  if (done) {
    return (
      <div className="mt-6 text-center">
        <IconShield className="mx-auto h-12 w-12 text-blue" />
        <h2 className="mt-3 text-lg font-bold text-ink">账号创建成功</h2>
        <p className="mx-auto mt-2 max-w-[300px] text-[13px] leading-relaxed text-muted">
          B 端功能需由店长/平台管理员为您绑定<span className="font-semibold text-ink">员工身份与门店</span>后方可使用。
          绑定完成后回到「登录」页进入工作台。
        </p>
        <p className="mt-4 rounded-xl bg-blue-soft px-4 py-3 text-left text-xs leading-relaxed text-blue-deep">
          提示：员工身份绑定接口属 B 端管理模块（后续段落地），当前演示账号已具备员工身份，可直接登录体验。
        </p>
      </div>
    )
  }

  return (
    <form onSubmit={(e) => void submit(e)} className="mt-6" noValidate>
      <h2 className="text-xl font-bold text-ink">创建账号</h2>
      <p className="mt-1 text-[13px] text-muted">手机号注册，等待管理员绑定员工身份后进入工作台</p>

      <div className="mt-5 space-y-4">
        <Field label="手机号" icon={<IconUser className="h-[18px] w-[18px]" />} error={errs.phone}>
          <input
            value={phone}
            onChange={(e) => setPhone(e.target.value.replace(/\D/g, '').slice(0, 11))}
            placeholder="请输入手机号"
            inputMode="numeric"
            autoComplete="username"
            className={inputCls}
          />
        </Field>
        <Field
          label="验证码"
          icon={<IconMsg className="h-[18px] w-[18px]" />}
          error={errs.code}
          extra={
            <button
              type="button"
              onClick={() => void sendCode()}
              disabled={sec > 0}
              className={cx(
                'shrink-0 rounded-lg px-3 py-1.5 text-xs font-semibold transition',
                sec > 0 ? 'bg-blue-soft text-muted' : 'bg-blue text-white hover:bg-blue-deep',
              )}
            >
              {sec > 0 ? `${sec}s 后重发` : '获取验证码'}
            </button>
          }
        >
          <input
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
            placeholder="6 位短信验证码"
            inputMode="numeric"
            className={inputCls}
          />
        </Field>
        <Field label="密码" icon={<IconLock className="h-[18px] w-[18px]" />} error={errs.password}>
          <input
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            type="password"
            placeholder="8-20 位，含字母和数字"
            autoComplete="new-password"
            className={inputCls}
          />
        </Field>
        <Field label="确认密码" icon={<IconLock className="h-[18px] w-[18px]" />} error={errs.confirm}>
          <input
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            type="password"
            placeholder="再次输入密码"
            autoComplete="new-password"
            className={inputCls}
          />
        </Field>

        <label className="flex cursor-pointer items-start gap-2 text-xs leading-relaxed text-muted">
          <input
            type="checkbox"
            checked={agree}
            onChange={(e) => setAgree(e.target.checked)}
            className="mt-0.5 h-4 w-4 accent-[#2E6BFF]"
          />
          <span>
            我已阅读并同意
            <span className="font-semibold text-blue">《用户协议》</span>和
            <span className="font-semibold text-blue">《隐私政策》</span>
          </span>
        </label>

        <button
          type="submit"
          disabled={busy}
          className="h-12 w-full rounded-xl bg-blue text-[15px] font-bold text-white shadow-soft transition hover:bg-blue-deep active:scale-[0.99] disabled:opacity-60"
        >
          {busy ? '注册中…' : '注 册'}
        </button>
      </div>
    </form>
  )
}
