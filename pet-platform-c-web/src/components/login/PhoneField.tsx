import { useId } from 'react'
import { cx } from '../../lib/cx'

interface PhoneFieldProps {
  value: string
  error: string
  onChange: (v: string) => void
}

/** 手机号输入：+86 固定前缀（纯文本，不用 emoji 国旗）、数字键盘、字段级中文错误。 */
export default function PhoneField({ value, error, onChange }: PhoneFieldProps) {
  const id = useId()
  const errId = `${id}-err`
  return (
    <div>
      <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-ink">
        手机号
      </label>
      <div
        className={cx(
          'flex items-center rounded-2xl border bg-white/80 px-4 shadow-soft backdrop-blur-sm transition',
          'focus-within:border-navy focus-within:ring-4 focus-within:ring-navy/10',
          error ? 'border-rose-300' : 'border-navy/12 hover:border-navy/25',
        )}
      >
        <span className="select-none pr-3 text-[15px] font-medium tracking-wide text-muted" aria-hidden="true">
          +86
        </span>
        <span className="h-5 w-px bg-navy/15" aria-hidden="true" />
        <input
          id={id}
          type="tel"
          inputMode="numeric"
          autoComplete="tel-national"
          maxLength={11}
          placeholder="请输入手机号"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          aria-invalid={!!error}
          aria-describedby={error ? errId : undefined}
          className="w-full bg-transparent px-3 py-3.5 text-[15px] text-ink placeholder:text-muted/60 focus:outline-none"
        />
      </div>
      {error && (
        <p id={errId} role="alert" className="mt-1.5 text-[13px] text-rose-500">
          {error}
        </p>
      )}
    </div>
  )
}
