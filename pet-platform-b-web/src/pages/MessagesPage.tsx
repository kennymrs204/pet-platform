import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useNavigate } from 'react-router-dom'
import Layout from '../components/layout/Layout'
import { IconBell, IconChat, IconChevronRight } from '../components/ui/icons'
import { useToast } from '../components/ui/Toast'
import { BizError } from '../api/types'
import { storeContext } from '../auth/storeContext'
import { notificationApi, NOTIFICATION_TYPE_TAG, notificationLink, type NotificationItem } from '../api/notification'
import { refreshUnreadCount, useUnreadCount } from '../lib/unreadCount'
import { inboxUnreadDisplay, showMarkAllRead } from '../lib/messageInboxCount'
import { timeAgo } from '../lib/dateText'
import { cx } from '../lib/cx'
import { isPagingTokenCurrent, planLoadMoreCommit, type PagingToken } from '../lib/messagesPaging'

const PAGE_SIZE = 20

/**
 * B 端消息中心（ADR-047）：门店收件箱。
 *
 * 三条纪律与 C 端消息中心（ADR-041）一致，刻意不另立口径：
 * 1. **一律以后端返回为准**——不在前端按订单/退款状态"猜"应该有什么消息；
 * 2. **点得动的前提是后端给了可认的 module**（见 api/notification.ts 的 notificationLink），
 *    认不出就只是一行可读的字，不绑跳转（迁移前的 legacy 行正是这种）；
 * 3. **已读写失败不假装成功**——本地置已读只为让这次点击不显得没反应，
 *    重新进入本页会以服务端为准回正。
 *
 * 这里的消息是"已经发生的事的告知"，不是待办清单：门店真正的待处理队列在订单页
 * （退款筛选）与托管页，读掉一条消息不会让那笔退款被批掉，文案与跳转都指向处理入口。
 */
export default function MessagesPage() {
  const navigate = useNavigate()
  const { push } = useToast()
  const ctx = useSyncExternalStore(storeContext.subscribe, storeContext.get)
  const storeId = ctx.storeId

  const [items, setItems] = useState<NotificationItem[] | null>(null)
  const [total, setTotal] = useState(0)
  const [pageNum, setPageNum] = useState(1)
  const [error, setError] = useState<string | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const [markingAll, setMarkingAll] = useState(false)
  // 分页代次与单飞闸：换店/卸载时 seq 自增并 abort 在途 loadMore；ref 同步判重，防连点重复追加同一页。
  const pagingSeqRef = useRef(0)
  const storeIdRef = useRef<string | null>(storeId)
  storeIdRef.current = storeId
  const loadingMoreRef = useRef(false)
  const loadMoreAbortRef = useRef<AbortController | null>(null)

  const load = useCallback(
    (signal: AbortSignal) => {
      if (!storeId) return
      setError(null)
      setItems(null)
      // 换店/重载时清掉上一店遗留的"加载更多中"态（旧请求的 finally 已被代次作废，不会再清）
      loadingMoreRef.current = false
      setLoadingMore(false)
      notificationApi
        .list(1, PAGE_SIZE, signal)
        .then((page) => {
          if (signal.aborted) return
          setItems(page.list ?? [])
          setTotal(Number(page.total ?? 0))
          setPageNum(1)
        })
        .catch((e: unknown) => {
          if (signal.aborted) return
          setItems(null)
          setError(e instanceof BizError ? e.message : '消息读取失败，请稍后重试')
        })
        .finally(() => {
          if (!signal.aborted) void refreshUnreadCount()
        })
    },
    [storeId],
  )

  useEffect(() => {
    const ac = new AbortController()
    load(ac.signal)
    return () => {
      ac.abort()
      // 门店切换/页面卸载：自增代次并取消在途 loadMore；旧响应即使到达也写不进新门店状态
      pagingSeqRef.current += 1
      loadMoreAbortRef.current?.abort()
      loadMoreAbortRef.current = null
      loadingMoreRef.current = false
    }
  }, [load])

  const loadMore = async () => {
    // 同步 ref 判重：setLoadingMore 要等下一次渲染，连点两下时 state 还没变，会重复请求同一页
    if (loadingMoreRef.current || !storeId) return
    loadingMoreRef.current = true
    setLoadingMore(true)
    const token: PagingToken = { storeId, seq: pagingSeqRef.current }
    const ac = new AbortController()
    loadMoreAbortRef.current = ac
    const stillCurrent = () => isPagingTokenCurrent(token, storeIdRef.current, pagingSeqRef.current)
    try {
      const next = pageNum + 1
      const page = await notificationApi.list(next, PAGE_SIZE, ac.signal)
      const plan = planLoadMoreCommit(token, storeIdRef.current, pagingSeqRef.current, page, next)
      // 换店/卸载后的迟到响应：列表、total、pageNum 一概不写（函数式并入保留并发本地改动）
      if (!plan.applied) return
      setItems((prev) => [...(prev ?? []), ...plan.append])
      setTotal(plan.total)
      setPageNum(plan.pageNum)
    } catch (e) {
      if (!stillCurrent()) return
      push(e instanceof BizError ? e.message : '加载更多失败', 'error')
    } finally {
      // 旧请求不得清掉新门店的 loading 态；只有当前请求才收尾
      if (stillCurrent()) {
        loadMoreAbortRef.current = null
        loadingMoreRef.current = false
        setLoadingMore(false)
      }
    }
  }

  const openItem = (item: NotificationItem) => {
    const to = notificationLink(item)
    if (!item.read) {
      // 先导航再补记已读：用户要的是立刻看到内容；写失败下次以服务端为准回正。
      // 未读数必须在写操作**落定之后**才重取：点下去就刷新等于和这次写入抢跑，
      // 结果角标停在"还没读"的数字上（浏览器验收 S10 实测到过），比慢半拍更糟的是它撒谎。
      setItems((prev) => prev?.map((n) => (n.id === item.id ? { ...n, read: true } : n)) ?? prev)
      notificationApi
        .markRead(item.id)
        .catch(() => undefined)
        .finally(() => void refreshUnreadCount())
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
      push(changed > 0 ? `已把 ${changed} 条消息标记为已读` : '本店上下文没有未读消息', 'success')
    } catch (e) {
      push(e instanceof BizError ? e.message : '标记已读失败', 'error')
    } finally {
      setMarkingAll(false)
    }
  }

  const unread = items?.filter((i) => !i.read).length ?? 0
  const hasMore = items !== null && items.length < total
  const storeName = ctx.candidates.find((s) => s.storeId === storeId)?.storeName ?? null
  /** 聊天未读分账（铃铛=total 的另一半；本入口只展示 chatCount，绝不与系统通知 read-all 互清）。 */
  const { summary } = useUnreadCount()
  const chatCount = summary?.chatCount ?? 0
  /**
   * 消息中心完整未读（服务端全量、含 CHAT_MESSAGE，与列表行 / markAllReadInStore 作用域一致）；
   * 不是排除了 chat 的铃铛 notificationCount。null=未知（unread-summary 首帧/失败）→ 头部显示 '—'、
   * 按钮保守回退当前页证据，绝不把"还不知道"当成真实 0。
   */
  const listUnread = inboxUnreadDisplay(summary?.listUnreadCount ?? null)

  return (
    <Layout active="messages">
      <div className="mx-auto flex max-w-[880px] flex-col gap-5 p-4 sm:p-6">
        <header className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <h1 className="text-xl font-bold text-ink">消息中心</h1>
            <p className="mt-1 text-xs text-muted">
              {storeName ? `当前门店：${storeName}` : '门店上下文加载中'}
              <span className="mx-1.5 text-line">|</span>
              未读 {listUnread === null ? '—' : listUnread} 条 / 共 {total} 条
            </p>
          </div>
          {showMarkAllRead(listUnread, unread) && (
            <button
              type="button"
              onClick={() => void markAll()}
              disabled={markingAll}
              className="h-9 rounded-xl bg-blue px-4 text-xs font-bold text-white transition hover:bg-blue-deep disabled:opacity-60"
            >
              {markingAll ? '处理中…' : '全部已读'}
            </button>
          )}
        </header>

        <p className="rounded-xl bg-blue-mist px-4 py-3 text-[11px] leading-relaxed text-muted">
          这里是系统业务通知（退款待处理、新预约待服务、照护异常），<b>读掉消息不等于处理了事情</b>——
          审批在订单页、护理记录在托管页。与客户的对话在「客户沟通」工作台，两处未读各自独立。
          切到店长/平台代管的另一家门店时，这里的未读数会跟着换店重取。
        </p>

        {/* 客户消息入口（P4）：未读数字来自 unread-summary 的 chatCount（真实分账，不含系统通知） */}
        <button
          type="button"
          onClick={() => navigate('/customer-chat')}
          className="flex items-center justify-between gap-3 rounded-2xl bg-white p-4 text-left shadow-soft ring-1 ring-line transition hover:ring-blue"
        >
          <span className="flex min-w-0 items-center gap-2.5">
            <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-blue-soft text-blue">
              <IconChat className="h-[18px] w-[18px]" />
            </span>
            <span className="min-w-0">
              <span className="block text-sm font-bold text-ink">
                客户消息
                {chatCount > 0 && (
                  <span className="ml-1.5 inline-flex h-4 min-w-[16px] items-center justify-center rounded-full bg-[#E8506B] px-1 text-[10px] font-bold text-white">
                    {chatCount > 99 ? '99+' : chatCount}
                  </span>
                )}
              </span>
              <span className="mt-0.5 block text-[11px] text-muted">
                {chatCount > 0 ? `${chatCount} 条未读 · 与客户的会话都在客户沟通工作台` : '与客户的会话都在客户沟通工作台'}
              </span>
            </span>
          </span>
          <span className="flex shrink-0 items-center gap-1 text-xs font-bold text-blue">
            去客户沟通 <IconChevronRight className="h-3.5 w-3.5" />
          </span>
        </button>

        {error && (
          <div className="rounded-2xl bg-white p-6 text-center shadow-soft ring-1 ring-rose-200">
            <p className="text-sm font-bold text-rose-700">消息没读出来</p>
            <p className="mt-1 text-xs text-rose-600/90">{error}</p>
            <button
              type="button"
              onClick={() => load(new AbortController().signal)}
              className="mt-4 h-9 rounded-xl bg-rose-600 px-4 text-xs font-bold text-white"
            >
              重试
            </button>
          </div>
        )}

        {!error && items === null && (
          <div className="flex flex-col gap-3" aria-busy="true">
            <SkeletonRow />
            <SkeletonRow />
          </div>
        )}

        {!error && items !== null && items.length === 0 && (
          <div className="rounded-2xl bg-white p-10 text-center shadow-soft ring-1 ring-line">
            <IconBell className="mx-auto h-8 w-8 text-muted/60" />
            <p className="mt-3 text-sm font-bold text-ink">这家店暂时没有新消息</p>
            <p className="mt-1 text-xs leading-relaxed text-muted">
              有客户提交退款申请、新的已支付预约或照护异常记录时，会通知到能处理这些动作的人。
            </p>
            <button
              type="button"
              onClick={() => navigate('/home')}
              className="mt-4 h-10 rounded-xl bg-blue px-5 text-sm font-bold text-white transition hover:bg-blue-deep"
            >
              回工作台
            </button>
          </div>
        )}

        {!error && items !== null && items.length > 0 && (
          <ul className="flex flex-col gap-3">
            {items.map((item) => (
              <MessageRow key={item.id} item={item} onOpen={() => openItem(item)} />
            ))}
          </ul>
        )}

        {!error && hasMore && (
          <button
            type="button"
            onClick={() => void loadMore()}
            disabled={loadingMore}
            className="h-10 rounded-xl bg-white text-xs font-bold text-blue ring-1 ring-line transition hover:bg-blue-mist disabled:opacity-60"
          >
            {loadingMore ? '加载中…' : `加载更多（已显示 ${items?.length ?? 0} / ${total}）`}
          </button>
        )}
      </div>
    </Layout>
  )
}

function MessageRow({ item, onOpen }: { item: NotificationItem; onOpen: () => void }) {
  const to = notificationLink(item)
  const tag = NOTIFICATION_TYPE_TAG[item.type]
  return (
    <li>
      <button
        type="button"
        onClick={onOpen}
        aria-label={`${item.title}${item.read ? '（已读）' : '（未读）'}`}
        className={cx(
          'w-full rounded-2xl p-4 text-left shadow-soft ring-1 transition hover:ring-blue',
          item.read ? 'bg-white/70 ring-line' : 'bg-white ring-blue/30',
        )}
      >
        <div className="flex items-start gap-2.5">
          <span
            aria-hidden="true"
            className={cx('mt-1.5 h-2 w-2 shrink-0 rounded-full', item.read ? 'bg-line' : 'bg-[#E8506B]')}
          />
          <div className="min-w-0 flex-1">
            <p className="flex items-center gap-2">
              <span className={cx('truncate text-sm', item.read ? 'font-semibold text-muted' : 'font-bold text-ink')}>
                {item.title}
              </span>
              {tag && (
                <span className="shrink-0 rounded-full bg-blue-soft px-2 py-0.5 text-[10px] font-bold text-blue">
                  {tag}
                </span>
              )}
            </p>
            <p className={cx('mt-1 text-xs leading-relaxed', item.read ? 'text-muted/80' : 'text-ink/80')}>
              {item.content}
            </p>
            <p className="mt-1.5 text-[11px] text-muted/70">{timeAgo(item.createdAt)}</p>
          </div>
          {to && <IconChevronRight className="mt-1 h-3.5 w-3.5 shrink-0 text-muted/60" />}
        </div>
      </button>
    </li>
  )
}

function SkeletonRow() {
  return (
    <div className="animate-pulse rounded-2xl bg-white p-4 ring-1 ring-line" aria-hidden="true">
      <div className="flex gap-2.5">
        <div className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-line" />
        <div className="flex-1">
          <div className="h-4 w-2/5 rounded bg-line" />
          <div className="mt-2 h-3 w-4/5 rounded bg-line" />
          <div className="mt-2 h-3 w-1/4 rounded bg-line" />
        </div>
      </div>
    </div>
  )
}
