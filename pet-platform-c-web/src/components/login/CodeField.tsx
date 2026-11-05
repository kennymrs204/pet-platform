import { useId } from 'react'
import { cx } from '../../lib/cx'

interface CodeFieldProps {
  value: string
  error: string
  sending: boolean
  countdown: number
  onChange: (v: string) => void
  onSend: () => void
}

/** 验证码输入 + 获取按钮（三态：获取验证码 / 发送中… / xx 秒后重新获取，60s 倒计时）。 */
export default function CodeField({ value, error, sending, countdown, onChange, onSend }: CodeFieldProps) {
  const id = useId()
  const errId = `${id}-err`
  const busy = sending || countdown > 0

  const btnText = sending ? '发送中…' : countdown > 0 ? `${countdown} 秒后重新获取` : '获取验证码'

  return (
    <div>
      <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-ink">
        短信验证码
      </label>
      <div
        className={cx(
          'flex items-center rounded-2xl border bg-white/80 pl-4 pr-1.5 shadow-soft backdrop-blur-sm transition',
          'focus-within:border-navy focus-within:ring-4 focus-within:ring-navy/10',
          error ? 'border-rose-300' : 'border-navy/12 hover:border-navy/25',
        )}
      >
        <input
          id={id}
          type="text"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          placeholder="请输入 6 位验证码"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          aria-invalid={!!error}
          aria-describedby={error ? errId : undefined}
          className="w-full min-w-0 bg-transparent py-3.5 pr-2 text-[15px] tracking-[0.2em] text-ink placeholder:tracking-normal placeholder:text-muted/60 focus:outline-none"
        />
        <button
          type="button"
          onClick={onSend}
          disabled={busy}
          className={cx(
            'shrink-0 whitespace-nowrap rounded-xl px-3.5 py-2.5 text-[13px] font-semibold transition',
            'focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-navy/15',
            busy
              ? 'cursor-not-allowed bg-warmgray text-muted'
              : 'bg-navy/[0.07] text-navy hover:bg-navy/[0.12] active:scale-[0.98]',
          )}
        >
          {btnText}
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
