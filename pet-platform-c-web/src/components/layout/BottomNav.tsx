import { useNavigate } from 'react-router-dom'
import { useToast } from '../ui/Toast'
import { cx } from '../../lib/cx'
import { IconCalendar, IconHouse, IconList, IconUser } from '../ui/icons'

/**
 * 底部主导航（全站唯一口径：首页 / 服务 / 订单 / 我的）。
 * 宠物档案属「我的」下的二级页，故不占顶级格；`to=null` 表示该页尚未交付，
 * 一律给诚实 toast，不做假跳转也不放占位数据。
 */
const NAV = [
  { key: 'home', label: '首页', to: '/home', Icon: IconHouse },
  { key: 'service', label: '服务', to: '/booking', Icon: IconCalendar },
  { key: 'orders', label: '订单', to: '/orders', Icon: IconList },
  { key: 'mine', label: '我的', to: '/profile', Icon: IconUser },
]

export type NavKey = (typeof NAV)[number]['key']

export default function BottomNav({ active }: { active: NavKey }) {
  const navigate = useNavigate()
  const { push } = useToast()
  return (
    <nav className="safe-bottom fixed inset-x-0 bottom-0 z-40">
      <div className="mx-auto flex max-w-md items-stretch justify-around border-t border-navy/5 bg-white/92 px-2 pb-2 pt-1.5 shadow-card backdrop-blur-xl">
        {NAV.map(({ key, label, to, Icon }) => {
          const on = key === active
          return (
            <button
              key={key}
              type="button"
              aria-current={on ? 'page' : undefined}
              onClick={() => {
                if (!to) return push(`「${label}」页面开发中，本轮先交付预约流程`, 'info')
                if (!on) navigate(to)
              }}
              className={cx(
                'flex flex-1 flex-col items-center gap-0.5 rounded-xl py-1.5 text-[11px] font-semibold transition',
                on ? 'text-navy' : 'text-muted hover:text-navy/70',
              )}
            >
              <Icon className="h-5 w-5" />
              {label}
              <span className={cx('h-0.5 w-5 rounded-full', on ? 'bg-gold' : 'bg-transparent')} />
            </button>
          )
        })}
      </div>
    </nav>
  )
}
