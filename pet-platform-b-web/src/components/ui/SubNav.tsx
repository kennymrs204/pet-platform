import { cx } from '../../lib/cx'
import type { IconComp } from '../../mock/dashboard'

/** 二级导航项：key 是分类标识，hint 供页面在内容区顶部转述口径 */
export interface SubNavItem {
  key: string
  label: string
  Icon: IconComp
  hint?: string
}

/**
 * 页面内二级导航（设置中心 / 客户分类等共用）：窄屏横向滚动、宽屏左侧固定。
 * 高亮与命中样式与全局一级导航（Layout 左侧）保持一致，避免同一后台两套选中态。
 * dirtyKeys 命中的项右侧出一个橙点（设置页用来标"有未保存的修改"）。
 */
export default function SubNav({
  items,
  active,
  onSelect,
  dirtyKeys = [],
  ariaLabel = '二级分类导航',
}: {
  items: SubNavItem[]
  active: string
  onSelect: (key: string) => void
  dirtyKeys?: string[]
  ariaLabel?: string
}) {
  return (
    <nav
      aria-label={ariaLabel}
      className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-1 lg:sticky lg:top-20 lg:mx-0 lg:flex-col lg:self-start lg:gap-1 lg:overflow-visible lg:px-0 lg:pb-0"
    >
      {items.map((item) => {
        const selected = item.key === active
        return (
          <button
            key={item.key}
            type="button"
            aria-current={selected ? 'page' : undefined}
            onClick={() => onSelect(item.key)}
            className={cx(
              'flex h-11 shrink-0 items-center gap-2.5 rounded-xl px-3.5 text-sm transition lg:w-full lg:gap-3 lg:px-4',
              selected ? 'bg-blue-soft font-bold text-blue' : 'bg-white text-ink ring-1 ring-line hover:bg-blue-mist lg:bg-transparent lg:ring-0',
            )}
          >
            <item.Icon className="h-[18px] w-[18px] shrink-0" />
            <span className="whitespace-nowrap">{item.label}</span>
            {dirtyKeys.includes(item.key) && <span className="ml-auto h-1.5 w-1.5 shrink-0 rounded-full bg-[#F59E0B]" title="有未保存的修改" />}
          </button>
        )
      })}
    </nav>
  )
}
