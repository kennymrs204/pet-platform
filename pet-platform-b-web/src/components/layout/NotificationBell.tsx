import { useNavigate } from 'react-router-dom'
import { IconBell } from '../ui/icons'
import { useUnreadCount } from '../../lib/unreadCount'

/**
 * 顶栏消息铃铛（ADR-047）：替掉那颗**硬编码 3 的假角标**——它从阶段 3 起就写着
 * "TODO: 未读数接 notification 段"，而门店消息其实早就在库里躺着，只是没人读。
 *
 * 未读数按当前门店上下文取（见 lib/unreadCount）：0 或未取到都不出角标，
 * 因为"不知道"和"确实没有"都不该被画成一个红点骗人。
 */
export default function NotificationBell() {
  const navigate = useNavigate()
  const { count } = useUnreadCount()
  const unread = count ?? 0
  return (
    <button
      type="button"
      aria-label={unread > 0 ? `消息中心，${unread} 条未读` : '消息中心'}
      onClick={() => navigate('/messages')}
      className="relative grid h-10 w-10 place-items-center rounded-full text-ink transition hover:bg-blue-mist"
    >
      <IconBell className="h-5 w-5" />
      {unread > 0 && (
        <span className="absolute right-1 top-1 grid h-4 min-w-[16px] place-items-center rounded-full bg-[#E8506B] px-1 text-[10px] font-bold text-white">
          {unread > 99 ? '99+' : unread}
        </span>
      )}
    </button>
  )
}
