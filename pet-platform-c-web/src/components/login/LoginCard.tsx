import { Link } from 'react-router-dom'
import type { LoginViewModel } from '../../auth/useLogin'
import AgreementNote from './AgreementNote'
import CodeField from './CodeField'
import LoginModeToggle from './LoginModeToggle'
import PasswordField from './PasswordField'
import PhoneField from './PhoneField'
import SubmitButton from './SubmitButton'

/** 登录卡片：验证码/密码双方式切换 + 注册入口（回车提交、字段级错误、loading 防重复）。 */
export default function LoginCard({ vm }: { vm: LoginViewModel }) {
  return (
    <div className="animate-fade-up rounded-4xl bg-white/85 p-7 shadow-card ring-1 ring-navy/5 backdrop-blur-xl sm:p-10">
      <h2 className="text-[22px] font-bold tracking-tight text-ink">欢迎回来</h2>
      <p className="mt-1.5 text-sm text-muted">使用手机号登录，支持验证码或密码</p>

      <div className="mt-6">
        <LoginModeToggle mode={vm.mode} onChange={vm.switchMode} />
      </div>

      <form
        className="mt-6 flex flex-col gap-5"
        onSubmit={(e) => {
          e.preventDefault()
          void vm.submit()
        }}
        noValidate
      >
        <PhoneField value={vm.phone} error={vm.phoneError} onChange={vm.setPhone} />
        {vm.mode === 'sms' ? (
          <CodeField
            value={vm.code}
            error={vm.codeError}
            sending={vm.sending}
            countdown={vm.countdown}
            onChange={vm.setCode}
            onSend={() => void vm.sendCode()}
          />
        ) : (
          <PasswordField
            label="密码"
            value={vm.password}
            error={vm.passwordError}
            onChange={vm.setPassword}
            autoComplete="current-password"
          />
        )}
        <SubmitButton loading={vm.loggingIn} disabled={!vm.canSubmit} text="登录" loadingText="登录中…" />
      </form>

      <p className="mt-4 text-center text-sm text-muted">
        <Link
          to="/reset-password"
          className="font-semibold text-navy underline decoration-navy/30 underline-offset-2 transition hover:decoration-navy focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-navy/25 rounded-sm"
        >
          忘记密码？
        </Link>
      </p>

      <p className="mt-2 text-center text-sm text-muted">
        还没有账号？
        <Link
          to="/register"
          className="ml-1 font-semibold text-navy underline decoration-navy/30 underline-offset-2 transition hover:decoration-navy focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-navy/25 rounded-sm"
        >
          立即注册
        </Link>
      </p>

      <div className="mt-5">
        <AgreementNote />
      </div>
    </div>
  )
}
