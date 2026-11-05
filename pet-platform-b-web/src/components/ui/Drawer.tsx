import { useEffect, useId, type ReactNode } from 'react'
import { IconXCircle } from './icons'
import { cx } from '../../lib/cx'

/**
 * 右侧抽屉（页面级表单/多步操作的承载件，与 Modal 同源：受控 + Esc 关闭 + 锁 body 滚动 + 遮罩关闭）。
 *
 * 结构三段：header（标题/说明/关闭）· content（**独立滚动**）· footer（**固定操作栏**，不随内容滚走）。
 * open=false 直接卸载子树——调用方把表单状态放在子树里，关闭即自然清空（不残留上次输入）。
 */
export default function Drawer({
  open,
  title,
  description,
  onClose,
  children,
  footer,
  width = 'sm:max-w-[520px]',
  headerNode,
  ariaLabel,
}: {
  open: boolean
  title: string
  description?: string
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
  /** 抽屉宽度（≥sm 生效；小屏恒全宽）。批量排班等复杂操作可加宽。 */
  width?: string
  /** 富头部（如头像+姓名+badge）：提供时替换默认标题/说明块，aria 用 ariaLabel 兜底 */
  headerNode?: ReactNode
  ariaLabel?: string
}) {
  const titleId = useId()
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    const prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      window.removeEventListener('keydown', onKey)
      document.body.style.overflow = prevOverflow
    }
  }, [open, onClose])

  if (!open) return null
  return (
    <div className="fixed inset-0 z-50">
      <div className="absolute inset-0 bg-[#1F2937]/30" aria-hidden="true" onClick={onClose} />
      <div
        role="dialog"
        aria-modal="true"
        {...(headerNode ? { 'aria-label': ariaLabel ?? title } : { 'aria-labelledby': titleId })}
        className={cx(
          'animate-fade-up absolute inset-y-0 right-0 flex w-full max-w-full flex-col bg-white shadow-card ring-1 ring-line',
          width,
        )}
      >
        <div className="flex items-start justify-between gap-3 border-b border-line px-5 py-4">
          {headerNode ? (
            <div className="min-w-0 flex-1">{headerNode}</div>
          ) : (
            <div className="min-w-0">
              <h2 id={titleId} className="text-lg font-black text-ink">
                {title}
              </h2>
              {description && <p className="mt-0.5 text-xs leading-relaxed text-muted">{description}</p>}
            </div>
          )}
          <button
            type="button"
            onClick={onClose}
            aria-label="关闭"
            className="-mr-1 -mt-1 grid h-8 w-8 shrink-0 place-items-center rounded-lg text-muted transition hover:bg-blue-mist hover:text-ink"
          >
            <IconXCircle className="h-5 w-5" />
          </button>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">{children}</div>
        {footer && <div className="shrink-0 border-t border-line bg-white px-5 py-3.5">{footer}</div>}
      </div>
    </div>
  )
}
