import { cx } from '../../lib/cx'

interface SubmitButtonProps {
  loading: boolean
  disabled: boolean
  text: string
  loadingText: string
}

/** 主按钮：蓝→靛蓝渐变，loading 时 spinner + 禁用防重复提交。 */
export default function SubmitButton({ loading, disabled, text, loadingText }: SubmitButtonProps) {
  return (
    <button
      type="submit"
      disabled={disabled}
      className={cx(
        'flex h-[52px] w-full items-center justify-center gap-2 rounded-2xl bg-btn-gradient text-[15px] font-semibold text-white shadow-btn transition',
        'focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-navy/25',
        disabled ? 'cursor-not-allowed opacity-55' : 'hover:brightness-110 active:scale-[0.99]',
      )}
    >
      {loading && (
        <svg className="h-5 w-5 animate-spin" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="2.6" opacity="0.3" />
          <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" />
        </svg>
      )}
      <span>{loading ? loadingText : text}</span>
    </button>
  )
}
