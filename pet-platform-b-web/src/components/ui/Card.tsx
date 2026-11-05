import type { ReactNode } from 'react'
import { IconChevronRight } from './icons'
import { cx } from '../../lib/cx'

/** 白卡壳：标题 + 可选「查看全部」入口（效果图通用样式） */
export default function Card({
  title,
  actionLabel = '查看全部',
  onAction,
  className,
  children,
}: {
  title: string
  actionLabel?: string
  onAction?: () => void
  className?: string
  children: ReactNode
}) {
  return (
    <section className={cx('rounded-2xl bg-white p-5 shadow-soft ring-1 ring-line', className)}>
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-base font-bold text-ink">{title}</h2>
        {onAction && (
          <button
            type="button"
            onClick={onAction}
            className="flex items-center gap-0.5 text-xs text-muted transition hover:text-blue"
          >
            {actionLabel} <IconChevronRight className="h-3.5 w-3.5" />
          </button>
        )}
      </div>
      {children}
    </section>
  )
}
