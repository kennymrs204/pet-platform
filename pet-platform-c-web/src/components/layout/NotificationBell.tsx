import { useNavigate } from 'react-router-dom'
import { IconBell } from '../ui/icons'
import { useUnreadCount } from '../../lib/unreadCount'

/**
 * 顶栏消息铃铛（ADR-041）：未读数 >0 才出角标，0 就只是个铃铛。
 *
 * 样式沿用主页原有那颗铃铛按钮（同一套 h-9 w-9 / bg-white-80 / shadow-soft），
 * 只把此前那颗**恒亮的假金点**换成真数据——它当年是"消息通道未开通"的装饰，现在不必了。
 */
export default function NotificationBell({ className = '' }: { className?: string }) {
  const navigate = useNavigate()
  const { count } = useUnreadCount()
  const unread = count ?? 0
  return (
    <button
      type="button"
      aria-label={unread > 0 ? `消息中心，${unread} 条未读` : '消息中心'}
      onClick={() => navigate('/messages')}
      className={`relative grid h-9 w-9 place-items-center rounded-full bg-white/80 text-navy shadow-soft ring-1 ring-navy/5 ${className}`}
    >
      <IconBell className="h-[18px] w-[18px]" />
      {unread > 0 && (
        <span className="absolute -right-1 -top-1 grid h-[17px] min-w-[17px] place-items-center rounded-full bg-rose-500 px-1 text-[10px] font-bold leading-none text-white ring-2 ring-ivory">
          {unread > 99 ? '99+' : unread}
        </span>
      )}
    </button>
  )
}
