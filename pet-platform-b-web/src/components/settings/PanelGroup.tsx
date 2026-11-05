import type { ReactNode } from 'react'
import { IconChevronDown } from '../ui/icons'
import { Badge } from './SettingsKit'
import { cx } from '../../lib/cx'
import type { IconComp } from '../../mock/dashboard'

/**
 * 手风琴分组壳。折叠只用 CSS 隐藏、不卸载子树——
 * 面板的表单 state 存在组件内部，卸载就等于丢弃用户输入；这里只改展示，不触发请求或保存。
 */
export default function PanelGroup({
  id,
  Icon,
  title,
  summary,
  meta,
  dirty,
  open,
  onToggle,
  children,
}: {
  id: string
  Icon: IconComp
  title: string
  /** 收起时展示的真实摘要（来自服务端数据或表单值，不编造） */
  summary?: ReactNode
  /** 标题右侧的状态徽章（只读/条数等真实状态） */
  meta?: ReactNode
  dirty?: boolean
  open: boolean
  onToggle: () => void
  children: ReactNode
}) {
  const bodyId = `settings-group-${id}`
  return (
    <section className="overflow-hidden rounded-2xl bg-white shadow-soft ring-1 ring-line">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={bodyId}
        onClick={onToggle}
        className="flex w-full items-center gap-3 px-4 py-3.5 text-left transition hover:bg-blue-mist sm:px-5"
      >
        <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-blue-soft text-blue">
          <Icon className="h-[18px] w-[18px]" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <span className="text-[15px] font-black leading-tight text-ink">{title}</span>
            {meta}
            {dirty && <Badge label="未保存" tone="orange" />}
          </span>
          {!open && summary && <span className="mt-1 block truncate text-[11px] leading-relaxed text-muted">{summary}</span>}
        </span>
        <IconChevronDown className={cx('h-4 w-4 shrink-0 text-muted transition-transform', open && 'rotate-180')} />
      </button>
      <div id={bodyId} className={cx(open ? 'block' : 'hidden')}>
        <div className="border-t border-line px-4 py-4 sm:px-5 sm:py-5">{children}</div>
      </div>
    </section>
  )
}
