import { Link } from 'react-router-dom'
import type { RegisterViewModel } from '../../auth/useRegister'
import CodeField from '../login/CodeField'
import PasswordField from '../login/PasswordField'
import PhoneField from '../login/PhoneField'
import SubmitButton from '../login/SubmitButton'
import AgreementNote from '../login/AgreementNote'

/** 注册卡片：手机号 + 验证码 + 设密（ADR-009 验证码注册）。 */
export default function RegisterCard({ vm }: { vm: RegisterViewModel }) {
  return (
    <div className="animate-fade-up rounded-4xl bg-white/85 p-7 shadow-card ring-1 ring-navy/5 backdrop-blur-xl sm:p-10">
      <h2 className="text-[22px] font-bold tracking-tight text-ink">创建账号</h2>
      <p className="mt-1.5 text-sm text-muted">使用手机号注册，成功后即可用密码或验证码登录</p>

      <form
        className="mt-7 flex flex-col gap-5"
        onSubmit={(e) => {
          e.preventDefault()
          void vm.submit()
        }}
        noValidate
      >
        <PhoneField value={vm.phone} error={vm.phoneError} onChange={vm.setPhone} />
        <CodeField
          value={vm.code}
          error={vm.codeError}
          sending={vm.sending}
          countdown={vm.countdown}
          onChange={vm.setCode}
          onSend={() => void vm.sendCode()}
        />
        <PasswordField
          label="设置密码"
          value={vm.password}
          error={vm.passwordError}
          onChange={vm.setPassword}
          placeholder="8-20 位，须含字母和数字"
          autoComplete="new-password"
        />
        <SubmitButton loading={vm.submitting} disabled={!vm.canSubmit} text="注册" loadingText="注册中…" />
      </form>

      <p className="mt-5 text-center text-sm text-muted">
        已有账号？
        <Link
          to="/login"
          className="ml-1 font-semibold text-navy underline decoration-navy/30 underline-offset-2 transition hover:decoration-navy focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-navy/25 rounded-sm"
        >
          返回登录
        </Link>
      </p>

      <div className="mt-5">
        <AgreementNote />
      </div>
    </div>
  )
}
