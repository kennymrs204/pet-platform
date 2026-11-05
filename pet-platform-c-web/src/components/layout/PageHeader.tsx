import { useNavigate } from 'react-router-dom'
import { IconChevronRight } from '../ui/icons'

/**
 * H5 业务页顶栏：返回 + 标题 + 可选右侧动作。
 * 返回优先走 history.back()，无历史（直接深链进来）时回落到 fallback。
 */
export default function PageHeader({
  title,
  fallback = '/home',
  right,
}: {
  title: string
  fallback?: string
  right?: { label: string; onClick: () => void }
}) {
  const navigate = useNavigate()
  return (
    <header className="sticky top-0 z-30 -mx-1 mb-1 flex items-center gap-2 bg-ivory/85 px-1 py-3 backdrop-blur">
      <button
        type="button"
        aria-label="返回"
        onClick={() => (window.history.length > 1 ? navigate(-1) : navigate(fallback, { replace: true }))}
        className="grid h-9 w-9 place-items-center rounded-full bg-white/85 text-navy shadow-soft ring-1 ring-navy/5"
      >
        <IconChevronRight className="h-4 w-4 rotate-180" />
      </button>
      <h1 className="min-w-0 flex-1 truncate text-base font-bold text-ink">{title}</h1>
      {right && (
        <button
          type="button"
          onClick={right.onClick}
          className="shrink-0 rounded-full bg-white/85 px-3 py-1.5 text-xs font-semibold text-navy ring-1 ring-navy/5"
        >
          {right.label}
        </button>
      )}
    </header>
  )
}
