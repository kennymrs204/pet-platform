import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react'
import { cx } from '../../lib/cx'

export type ToastTone = 'info' | 'success' | 'error'

interface ToastItem {
  id: number
  msg: string
  tone: ToastTone
}
interface ToastCtx {
  push: (msg: string, tone?: ToastTone) => void
}

const Ctx = createContext<ToastCtx | null>(null)

/** 全局轻量提示（替代浏览器原生 alert）。顶部居中、自动消失、aria-live 播报。 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])
  const seq = useRef(0)

  const remove = useCallback((id: number) => {
    setItems((xs) => xs.filter((x) => x.id !== id))
  }, [])

  const push = useCallback(
    (msg: string, tone: ToastTone = 'info') => {
      const id = ++seq.current
      setItems((xs) => [...xs, { id, msg, tone }])
      window.setTimeout(() => remove(id), 3400)
    },
    [remove],
  )

  const value = useMemo(() => ({ push }), [push])

  return (
    <Ctx.Provider value={value}>
      {children}
      <div
        className="pointer-events-none fixed inset-x-0 top-4 z-50 flex flex-col items-center gap-2 px-4"
        role="region"
        aria-live="polite"
        aria-label="通知"
      >
        {items.map((t) => (
          <div
            key={t.id}
            role="status"
            className={cx(
              'animate-toast-in pointer-events-auto flex max-w-[92vw] items-center gap-2.5 rounded-2xl border bg-white/92 px-4 py-3 text-sm shadow-card backdrop-blur-md sm:max-w-sm',
              toneBorder(t.tone),
            )}
          >
            <ToneIcon tone={t.tone} />
            <span className="text-ink">{t.msg}</span>
          </div>
        ))}
      </div>
    </Ctx.Provider>
  )
}

export function useToast(): ToastCtx {
  const ctx = useContext(Ctx)
  if (!ctx) throw new Error('useToast 必须在 ToastProvider 内使用')
  return ctx
}

function toneBorder(tone: ToastTone): string {
  if (tone === 'success') return 'border-emerald-200/70'
  if (tone === 'error') return 'border-rose-200/70'
  return 'border-line'
}

function ToneIcon({ tone }: { tone: ToastTone }) {
  const cls = 'h-5 w-5 shrink-0'
  if (tone === 'success') {
    return (
      <svg viewBox="0 0 24 24" fill="none" className={cx(cls, 'text-emerald-500')} aria-hidden="true">
        <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.6" opacity="0.35" />
        <path d="M8 12.4l2.6 2.6L16 9.6" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    )
  }
  if (tone === 'error') {
    return (
      <svg viewBox="0 0 24 24" fill="none" className={cx(cls, 'text-rose-500')} aria-hidden="true">
        <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.6" opacity="0.35" />
        <path d="M12 7.4v5.6" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" />
        <circle cx="12" cy="16.3" r="1.05" fill="currentColor" />
      </svg>
    )
  }
  return (
    <svg viewBox="0 0 24 24" fill="none" className={cx(cls, 'text-blue')} aria-hidden="true">
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.6" opacity="0.35" />
      <path d="M12 11v5.6" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" />
      <circle cx="12" cy="7.7" r="1.05" fill="currentColor" />
    </svg>
  )
}
