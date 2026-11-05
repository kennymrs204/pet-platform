import { useId, useState } from 'react'
import { cx } from '../../lib/cx'

interface PasswordFieldProps {
  label: string
  value: string
  error: string
  onChange: (v: string) => void
  placeholder?: string
  autoComplete?: 'current-password' | 'new-password'
}

/** 密码输入：显隐切换 + 字段级中文错误。 */
export default function PasswordField({
  label,
  value,
  error,
  onChange,
  placeholder = '请输入密码',
  autoComplete = 'current-password',
}: PasswordFieldProps) {
  const id = useId()
  const errId = `${id}-err`
  const [visible, setVisible] = useState(false)

  return (
    <div>
      <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-ink">
        {label}
      </label>
      <div
        className={cx(
          'flex items-center rounded-2xl border bg-white/80 px-4 shadow-soft backdrop-blur-sm transition',
          'focus-within:border-navy focus-within:ring-4 focus-within:ring-navy/10',
          error ? 'border-rose-300' : 'border-navy/12 hover:border-navy/25',
        )}
      >
        <input
          id={id}
          type={visible ? 'text' : 'password'}
          autoComplete={autoComplete}
          maxLength={20}
          placeholder={placeholder}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          aria-invalid={!!error}
          aria-describedby={error ? errId : undefined}
          className="w-full bg-transparent py-3.5 text-[15px] text-ink placeholder:text-muted/60 focus:outline-none"
        />
        <button
          type="button"
          onClick={() => setVisible((v) => !v)}
          aria-label={visible ? '隐藏密码' : '显示密码'}
          aria-pressed={visible}
          className="shrink-0 rounded-lg p-1.5 text-muted transition hover:text-navy focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-navy/25"
        >
          {visible ? (
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5" aria-hidden="true">
              <path d="M3 3l18 18" />
              <path d="M10.6 5.2A9.8 9.8 0 0 1 12 5c5 0 9 4.5 9.5 7-.2 1.2-1.2 2.9-2.8 4.3M6.2 6.9C3.9 8.4 2.8 10.7 2.5 12c.5 2.5 4.5 7 9.5 7 1.5 0 2.9-.4 4.1-1" />
              <path d="M9.9 10.2a3 3 0 0 0 4.1 4.2" />
            </svg>
          ) : (
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5" aria-hidden="true">
              <path d="M2.5 12C3 9.5 7 5 12 5s9 4.5 9.5 7c-.5 2.5-4.5 7-9.5 7s-9-4.5-9.5-7z" />
              <circle cx="12" cy="12" r="3" />
            </svg>
          )}
        </button>
      </div>
      {error && (
        <p id={errId} role="alert" className="mt-1.5 text-[13px] text-rose-500">
          {error}
        </p>
      )}
    </div>
  )
}
