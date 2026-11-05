import { cx } from '../../lib/cx'
import type { LoginMode } from '../../auth/useLogin'

interface LoginModeToggleProps {
  mode: LoginMode
  onChange: (m: LoginMode) => void
}

const OPTIONS: { value: LoginMode; label: string }[] = [
  { value: 'sms', label: '验证码登录' },
  { value: 'password', label: '密码登录' },
]

/** 登录方式分段切换（role=radiogroup，键盘可达）。 */
export default function LoginModeToggle({ mode, onChange }: LoginModeToggleProps) {
  return (
    <div role="radiogroup" aria-label="登录方式" className="flex rounded-2xl bg-warmgray p-1">
      {OPTIONS.map((o) => {
        const active = mode === o.value
        return (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => onChange(o.value)}
            className={cx(
              'flex-1 rounded-xl py-2 text-sm font-semibold transition',
              'focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-navy/15',
              active ? 'bg-white text-navy shadow-soft' : 'text-muted hover:text-ink',
            )}
          >
            {o.label}
          </button>
        )
      })}
    </div>
  )
}
