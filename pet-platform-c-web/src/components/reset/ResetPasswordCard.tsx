import { Link } from 'react-router-dom'
import type { ResetPasswordViewModel } from '../../auth/useResetPassword'
import CodeField from '../login/CodeField'
import PasswordField from '../login/PasswordField'
import PhoneField from '../login/PhoneField'
import SubmitButton from '../login/SubmitButton'

/** 忘记密码表单（ADR-011）：手机号 + RESET 验证码 + 新密码；确认密码仅前端校验。 */
export default function ResetPasswordCard({ vm }: { vm: ResetPasswordViewModel }) {
  return (
    <div className="animate-fade-up rounded-4xl bg-white/85 p-7 shadow-card ring-1 ring-navy/5 backdrop-blur-xl sm:p-10">
      <h2 className="text-[22px] font-bold tracking-tight text-ink">重置密码</h2>
      <p className="mt-1.5 text-sm text-muted">验证手机号后设置新密码，成功后请重新登录</p>

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
          label="新密码"
          value={vm.password}
          error={vm.passwordError}
          onChange={vm.setPassword}
          placeholder="8-20 位，须含字母和数字"
          autoComplete="new-password"
        />
        <PasswordField
          label="确认新密码"
          value={vm.confirm}
          error={vm.confirmError}
          onChange={vm.setConfirm}
          placeholder="再次输入新密码"
          autoComplete="new-password"
        />
        {vm.formError && (
          <p role="alert" className="rounded-xl bg-rose-50 px-3 py-2 text-[13px] text-rose-600">
            {vm.formError}
          </p>
        )}
        <SubmitButton
          loading={vm.submitting}
          disabled={!vm.canSubmit}
          text="重置密码"
          loadingText="重置中…"
        />
      </form>

      <p className="mt-5 text-center text-sm text-muted">
        想起密码了？
        <Link
          to="/login"
          className="ml-1 font-semibold text-navy underline decoration-navy/30 underline-offset-2 transition hover:decoration-navy focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-navy/25 rounded-sm"
        >
          返回登录
        </Link>
      </p>
    </div>
  )
}
