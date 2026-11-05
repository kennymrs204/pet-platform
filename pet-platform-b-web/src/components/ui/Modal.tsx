import { useEffect, useId, type ReactNode } from 'react'
import { IconXCircle } from './icons'

/**
 * 轻量居中弹窗（项目首个通用 Modal，零依赖）。
 * 形态照搬既有内联模式：Layout 的 fixed 遮罩 + 门店/用户菜单的白卡（shadow-card + ring-line）。
 *
 * 约束：受控（open/onClose）；打开时锁 body 滚动、支持 Esc 关闭；
 * open=false 直接卸载子树——调用方把表单状态放在子树里，关闭即自然清空（不残留上次输入）。
 */
export default function Modal({
  open,
  title,
  description,
  onClose,
  children,
  footer,
  width = 'max-w-[460px]',
}: {
  open: boolean
  title: string
  description?: string
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
  width?: string
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
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-[#1F2937]/30" aria-hidden="true" onClick={onClose} />
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className={`animate-fade-up relative flex max-h-full w-full flex-col ${width} rounded-2xl bg-white p-5 shadow-card ring-1 ring-line`}
      >
        <div className="flex shrink-0 items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 id={titleId} className="text-lg font-black text-ink">{title}</h2>
            {description && <p className="mt-1 text-xs leading-relaxed text-muted">{description}</p>}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="关闭"
            className="-mr-1 -mt-1 grid h-8 w-8 shrink-0 place-items-center rounded-lg text-muted transition hover:bg-blue-mist hover:text-ink"
          >
            <IconXCircle className="h-5 w-5" />
          </button>
        </div>
        {/* 内容区自滚动：弹窗打开时 body 滚动被锁，超高内容（如代客建单的全量时段格）
            不给内滚就会把底部操作栏顶出视口，确认按钮永远点不到 */}
        <div className="mt-4 min-h-0 overflow-y-auto">{children}</div>
        {footer && <div className="mt-5 flex shrink-0 justify-end gap-2.5">{footer}</div>}
      </div>
    </div>
  )
}
