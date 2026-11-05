import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { BizError } from '../api/types'
import { NOTIFICATION_TYPE_TAG, NotificationItem, notificationApi, notificationLink } from '../api/notification'
import { chatApi, type ChatConversationVO } from '../api/chat'
import { tokenStore } from '../auth/tokenStore'
import { useBizFail } from '../auth/useBizFail'
import PageHeader from '../components/layout/PageHeader'
import { useToast } from '../components/ui/Toast'
import { cx } from '../lib/cx'
import { relativeTimeText } from '../lib/dateText'
import { refreshUnreadCount, useUnreadCount } from '../lib/unreadCount'
import { showMarkAllRead } from '../lib/messageInboxCount'
import { refreshUnreadAfterMarkRead } from '../lib/markReadRefresh'
import { IconBell, IconChat, IconChevronRight } from '../components/ui/icons'

const PAGE_SIZE = 20

/**
 * C 端消息中心：**系统通知 × 门店沟通** 两个分类（P3）。
 *
 * 数据来源纪律：两侧列表与未读**一律以后端返回为准**，不在前端按订单状态推断"应该有什么消息"；
 * 系统通知点击跳转只认 biz_ref 的 module（api/notification.ts 的 notificationLink），
 * 门店沟通点击进 /chat/:conversationId。
 *
 * 分类边界（刻意保持）：
 *  - 「全部已读」只在系统通知分类显示，且只调系统通知的 read-all——聊天已读只能由聊天页的 read 处理；
 *  - 两个分类的数据 state 都在本组件顶层（切 tab 仅切换渲染），切换不丢失任何一类已读状态；
 *  - 进入门店沟通分类不标读任何聊天（未读消费只发生在用户真正打开会话后）。
 *
 * 已读失败（网络/10004）不假装成功：本地状态照常更新只为让这一次点击不显得没反应，
 * 但重新进入本页会以服务端为准回正，未读数同样向服务端要。
 */
export default function MessagesPage() {
  const navigate = useNavigate()
  const { push } = useToast()
  const fail = useBizFail()
  const userId = tokenStore.getUserId()

  const [tab, setTab] = useState<'system' | 'chat'>('system')

  // ---- 系统通知（原有行为原样保留） ----
  const [items, setItems] = useState<NotificationItem[] | null>(null)
  const [total, setTotal] = useState(0)
  const [pageNum, setPageNum] = useState(1)
  const [error, setError] = useState<string | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const [markingAll, setMarkingAll] = useState(false)

  // ---- 门店沟通（P3；state 顶层保留，切 tab 不丢） ----
  const [convItems, setConvItems] = useState<ChatConversationVO[] | null>(null)
  const [convTotal, setConvTotal] = useState(0)
  const [convPageNum, setConvPageNum] = useState(1)
  const [convError, setConvError] = useState<string | null>(null)
  const [convLoadingMore, setConvLoadingMore] = useState(false)

  /** 分账未读（系统通知/门店沟通各自的数量；total 已是两源合计，见 lib/unreadCount）。 */
  const { summary } = useUnreadCount()

  useEffect(() => {
    if (!userId) navigate('/login', { replace: true })
  }, [userId, navigate])

  const load = useCallback(async (signal: AbortSignal) => {
    setError(null)
    setItems(null)
    try {
      const page = await notificationApi.list(1, PAGE_SIZE, signal)
      if (signal.aborted) return
      setItems(page.list ?? [])
      setTotal(Number(page.total ?? 0))
      setPageNum(1)
    } catch (e) {
      if (signal.aborted) return
      setItems(null)
      setError(e instanceof BizError ? e.message : '消息读取失败，请稍后重试')
    } finally {
      if (!signal.aborted) void refreshUnreadCount()
    }
  }, [])

  useEffect(() => {
    const ac = new AbortController()
    load(ac.signal)
    return () => ac.abort()
  }, [load])

  const loadConv = useCallback(async (signal: AbortSignal) => {
    setConvError(null)
    setConvItems(null)
    try {
      const page = await chatApi.conversations(1, PAGE_SIZE, signal)
      if (signal.aborted) return
      setConvItems(page.list ?? [])
      setConvTotal(Number(page.total ?? 0))
      setConvPageNum(1)
    } catch (e) {
      if (signal.aborted) return
      setConvItems(null)
      setConvError(e instanceof BizError ? e.message : '会话列表读取失败，请稍后重试')
    }
  }, [])

  useEffect(() => {
    const ac = new AbortController()
    loadConv(ac.signal)
    return () => ac.abort()
  }, [loadConv])

  const loadMore = async () => {
    if (loadingMore) return
    setLoadingMore(true)
    try {
      const next = pageNum + 1
      const page = await notificationApi.list(next, PAGE_SIZE)
      setItems((prev) => [...(prev ?? []), ...(page.list ?? [])])
      setTotal(Number(page.total ?? 0))
      setPageNum(next)
    } catch (e) {
      fail(e)
    } finally {
      setLoadingMore(false)
    }
  }

  const loadMoreConv = async () => {
    if (convLoadingMore) return
    setConvLoadingMore(true)
    try {
      const next = convPageNum + 1
      const page = await chatApi.conversations(next, PAGE_SIZE)
      setConvItems((prev) => [...(prev ?? []), ...(page.list ?? [])])
      setConvTotal(Number(page.total ?? 0))
      setConvPageNum(next)
    } catch (e) {
      fail(e)
    } finally {
      setConvLoadingMore(false)
    }
  }

  const openItem = async (item: NotificationItem) => {
    const to = notificationLink(item)
    if (!item.read) {
      // 先导航再补记已读：用户要的是立刻看到内容，写失败也不该把他挡在门口。
      // 失败时本地仍置已读，但下一次 load/未读数刷新会以服务端为准回正。
      setItems((prev) => prev?.map((n) => (n.id === item.id ? { ...n, read: true, readAt: n.readAt ?? '' } : n)) ?? prev)
      void refreshUnreadAfterMarkRead(notificationApi.markRead(item.id), () => {
        void refreshUnreadCount()
      })
    }
    if (to) navigate(to)
  }

  const markAll = async () => {
    if (markingAll) return
    setMarkingAll(true)
    try {
      const changed = Number(await notificationApi.markAllRead())
      setItems((prev) => prev?.map((n) => (n.read ? n : { ...n, read: true })) ?? prev)
      void refreshUnreadCount()
      push(changed > 0 ? `已把 ${changed} 条消息标记为已读` : '没有未读消息', 'success')
    } catch (e) {
      fail(e)
    } finally {
      setMarkingAll(false)
    }
  }

  const unread = items?.filter((i) => !i.read).length ?? 0
  const hasMore = items !== null && items.length < total
  const convHasMore = convItems !== null && convItems.length < convTotal

  return (
    <main className="min-h-[100dvh] bg-brand-gradient">
      <div className="mx-auto max-w-md px-4 pb-16">
        <PageHeader
          title="消息中心"
          fallback="/home"
          right={
            // 「全部已读」以服务端个人收件箱全量未读为准（跨分页），不再只看当前页 items；
            // 未知（summary=null）时保守回退当前页证据，绝不因虚构的 0 谎称已全部读完。
            tab === 'system' && showMarkAllRead(summary?.notificationCount ?? null, unread)
              ? { label: markingAll ? '处理中…' : '全部已读', onClick: () => void markAll() }
              : undefined
          }
        />

        {/* 分类（默认系统通知，保留原有使用习惯；radio 组键盘可达，与登录方式切换同款视觉） */}
        <div role="radiogroup" aria-label="消息分类" className="mt-3 flex rounded-2xl bg-warmgray p-1">
          {(['system', 'chat'] as const).map((k) => {
            const active = tab === k
            const badge = k === 'system' ? (summary?.notificationCount ?? 0) : (summary?.chatCount ?? 0)
            return (
              <button
                key={k}
                type="button"
                role="radio"
                aria-checked={active}
                onClick={() => setTab(k)}
                className={cx(
                  'flex flex-1 items-center justify-center gap-1.5 rounded-xl py-2 text-sm font-semibold transition',
                  active ? 'bg-white text-navy shadow-soft' : 'text-muted hover:text-ink',
                )}
              >
                {k === 'system' ? '系统通知' : '门店沟通'}
                {badge > 0 && (
                  <span className="rounded-full bg-rose-500 px-1.5 text-[10px] font-bold leading-4 text-white">
                    {badge > 99 ? '99+' : badge}
                  </span>
                )}
              </button>
            )
          })}
        </div>

        <p className="mt-2 px-1 text-[11px] text-muted">
          {tab === 'system'
            ? '系统通知：下单、支付、取消与退款进度等业务动态；订单与退款详情以订单页为准。'
            : '门店沟通：与门店的一对一会话，按最近联系时间排序；消息记录仅你与对应门店可见。'}
        </p>

        {/* ---------- 系统通知 ---------- */}
        {tab === 'system' && (
          <>
            {error && (
              <div className="mt-4 rounded-3xl border border-rose-200 bg-rose-50/90 p-5 text-center shadow-soft">
                <p className="text-sm font-semibold text-rose-700">消息没读出来</p>
                <p className="mt-1 text-xs leading-relaxed text-rose-600/90">{error}</p>
                <button
                  type="button"
                  onClick={() => void load(new AbortController().signal)}
                  className="mt-4 inline-flex h-9 items-center rounded-2xl bg-rose-600 px-4 text-xs font-semibold text-white"
                >
                  重试
                </button>
              </div>
            )}

            {!error && items === null && (
              <div className="mt-4 space-y-3" aria-busy="true">
                <SkeletonRow />
                <SkeletonRow />
              </div>
            )}

            {!error && items !== null && items.length === 0 && (
              <div className="mt-6 rounded-3xl bg-white/85 p-8 text-center shadow-soft ring-1 ring-navy/5">
                <IconBell className="mx-auto h-8 w-8 text-gold-dark/50" />
                <p className="mt-3 text-sm font-semibold text-ink">还没有消息</p>
                <p className="mt-1 text-xs leading-relaxed text-muted">
                  下单、支付、取消预约或退款进度变化时，系统会在这里通知你。
                </p>
                <button
                  type="button"
                  onClick={() => navigate('/home')}
                  className="mt-4 inline-flex h-10 items-center rounded-2xl bg-btn-gradient px-5 text-sm font-semibold text-white shadow-btn"
                >
                  回主页
                </button>
              </div>
            )}

            {!error && items !== null && items.length > 0 && (
              <ul className="mt-3 space-y-3">
                {items.map((item, i) => (
                  <MessageRow key={item.id} item={item} delay={i * 50} onOpen={() => void openItem(item)} />
                ))}
              </ul>
            )}

            {!error && hasMore && (
              <button
                type="button"
                onClick={() => void loadMore()}
                disabled={loadingMore}
                className="mt-4 w-full rounded-2xl bg-white/85 py-2.5 text-xs font-semibold text-navy ring-1 ring-navy/5 disabled:opacity-60"
              >
                {loadingMore ? '加载中…' : `加载更多（已显示 ${items?.length ?? 0} / ${total}）`}
              </button>
            )}
          </>
        )}

        {/* ---------- 门店沟通 ---------- */}
        {tab === 'chat' && (
          <>
            {convError && (
              <div className="mt-4 rounded-3xl border border-rose-200 bg-rose-50/90 p-5 text-center shadow-soft">
                <p className="text-sm font-semibold text-rose-700">会话没读出来</p>
                <p className="mt-1 text-xs leading-relaxed text-rose-600/90">{convError}</p>
                <button
                  type="button"
                  onClick={() => void loadConv(new AbortController().signal)}
                  className="mt-4 inline-flex h-9 items-center rounded-2xl bg-rose-600 px-4 text-xs font-semibold text-white"
                >
                  重试
                </button>
              </div>
            )}

            {!convError && convItems === null && (
              <div className="mt-4 space-y-3" aria-busy="true">
                <SkeletonRow />
                <SkeletonRow />
              </div>
            )}

            {!convError && convItems !== null && convItems.length === 0 && (
              <div className="mt-6 rounded-3xl bg-white/85 p-8 text-center shadow-soft ring-1 ring-navy/5">
                <IconChat className="mx-auto h-8 w-8 text-gold-dark/50" />
                <p className="mt-3 text-sm font-semibold text-ink">还没有和门店沟通过</p>
                <p className="mt-1 text-xs leading-relaxed text-muted">
                  你可以在订单详情中点击「联系门店」，开始沟通。
                </p>
                <button
                  type="button"
                  onClick={() => navigate('/orders')}
                  className="mt-4 inline-flex h-10 items-center rounded-2xl bg-btn-gradient px-5 text-sm font-semibold text-white shadow-btn"
                >
                  去看我的订单
                </button>
              </div>
            )}

            {!convError && convItems !== null && convItems.length > 0 && (
              <ul className="mt-3 space-y-3">
                {convItems.map((c, i) => (
                  <ConversationRow key={c.id} conv={c} delay={i * 50} />
                ))}
              </ul>
            )}

            {!convError && convHasMore && (
              <button
                type="button"
                onClick={() => void loadMoreConv()}
                disabled={convLoadingMore}
                className="mt-4 w-full rounded-2xl bg-white/85 py-2.5 text-xs font-semibold text-navy ring-1 ring-navy/5 disabled:opacity-60"
              >
                {convLoadingMore ? '加载中…' : `加载更多（已显示 ${convItems?.length ?? 0} / ${convTotal}）`}
              </button>
            )}
          </>
        )}
      </div>
    </main>
  )
}

function MessageRow({ item, delay, onOpen }: { item: NotificationItem; delay: number; onOpen: () => void }) {
  const to = notificationLink(item)
  const tag = NOTIFICATION_TYPE_TAG[item.type]
  return (
    <li className="animate-fade-up" style={{ animationDelay: `${delay}ms` }}>
      <button
        type="button"
        onClick={onOpen}
        aria-label={`${item.title}${item.read ? '（已读）' : '（未读）'}`}
        className={cx(
          'w-full rounded-3xl p-4 text-left shadow-soft ring-1 transition active:scale-[0.995]',
          item.read ? 'bg-white/60 ring-navy/5' : 'bg-white/95 ring-gold/30',
        )}
      >
        <div className="flex items-start gap-2.5">
          <span
            aria-hidden="true"
            className={cx('mt-1.5 h-2 w-2 shrink-0 rounded-full', item.read ? 'bg-navy/15' : 'bg-rose-500')}
          />
          <div className="min-w-0 flex-1">
            <p className="flex items-center gap-2">
              <span className={cx('truncate text-sm', item.read ? 'font-semibold text-muted' : 'font-bold text-ink')}>
                {item.title}
              </span>
              {tag && (
                <span className="shrink-0 rounded-full bg-warmgray px-2 py-0.5 text-[10px] font-semibold text-navy/70">
                  {tag}
                </span>
              )}
            </p>
            <p className={cx('mt-1 text-xs leading-relaxed', item.read ? 'text-muted/80' : 'text-navy/75')}>
              {item.content}
            </p>
            <p className="mt-1.5 text-[11px] text-muted/80">{relativeTimeText(item.createdAt)}</p>
          </div>
          {to && <IconChevronRight className="mt-1 h-3.5 w-3.5 shrink-0 text-muted/60" />}
        </div>
      </button>
    </li>
  )
}

/** 会话行（门店沟通分类）：门店名 / 摘要 / 时间 / 未读；原单来源只做弱标注（不称"当前订单"）。 */
function ConversationRow({ conv: c, delay }: { conv: ChatConversationVO; delay: number }) {
  const navigate = useNavigate()
  const unreadCount = Number(c.unread)
  const unread = Number.isFinite(unreadCount) && unreadCount > 0 ? unreadCount : 0
  return (
    <li className="animate-fade-up" style={{ animationDelay: `${delay}ms` }}>
      <button
        type="button"
        aria-label={`${c.storeName}${unread > 0 ? `（${unread} 条未读）` : ''}`}
        onClick={() => navigate(`/chat/${c.id}`)}
        className={cx(
          'w-full rounded-3xl p-4 text-left shadow-soft ring-1 transition active:scale-[0.995]',
          unread > 0 ? 'bg-white/95 ring-gold/30' : 'bg-white/60 ring-navy/5',
        )}
      >
        <div className="flex items-start gap-3">
          <span className="grid h-10 w-10 shrink-0 place-items-center rounded-2xl bg-ivory text-gold-dark ring-1 ring-gold/25">
            <IconChat className="h-5 w-5" />
          </span>
          <div className="min-w-0 flex-1">
            <p className="flex items-center gap-2">
              <span className="truncate text-sm font-bold text-ink">{c.storeName}</span>
              {c.originBizModule === 'appointment' && (
                <span className="shrink-0 rounded-full bg-warmgray px-2 py-0.5 text-[10px] font-semibold text-navy/70">
                  关联订单
                </span>
              )}
            </p>
            <p className="mt-1 truncate text-xs text-muted">{c.lastMessagePreview || '还没有消息，打个招呼吧'}</p>
            <p className="mt-1 text-[11px] text-muted/80">
              {c.lastMessageAt ? relativeTimeText(c.lastMessageAt) : ''}
            </p>
          </div>
          <span className="flex shrink-0 flex-col items-end gap-1.5">
            {unread > 0 && (
              <span className="grid h-[18px] min-w-[18px] place-items-center rounded-full bg-rose-500 px-1 text-[10px] font-bold text-white">
                {unread > 99 ? '99+' : unread}
              </span>
            )}
            <IconChevronRight className="h-3.5 w-3.5 text-muted/60" />
          </span>
        </div>
      </button>
    </li>
  )
}

function SkeletonRow() {
  return (
    <div className="animate-pulse rounded-3xl bg-white/70 p-4 ring-1 ring-navy/5" aria-hidden="true">
      <div className="flex gap-2.5">
        <div className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-warmgray" />
        <div className="flex-1">
          <div className="h-4 w-2/5 rounded bg-warmgray" />
          <div className="mt-2 h-3 w-4/5 rounded bg-warmgray" />
          <div className="mt-2 h-3 w-1/4 rounded bg-warmgray" />
        </div>
      </div>
    </div>
  )
}
