import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useSearchParams } from 'react-router-dom'
import Layout from '../components/layout/Layout'
import { IconChat, IconImage, IconSearch } from '../components/ui/icons'
import Modal from '../components/ui/Modal'
import { useToast } from '../components/ui/Toast'
import { BizError } from '../api/types'
import { chatApi, newClientMsgId, type StoreChatMessageVO, type StoreConversationVO } from '../api/chat'
import { UploadError, uploadImage, validateImage } from '../api/upload'
import { storeContext } from '../auth/storeContext'
import { refreshUnreadCount } from '../lib/unreadCount'
import { timeAgo } from '../lib/dateText'
import { cx } from '../lib/cx'
import {
  cmpId,
  commitIncoming,
  isChatContextCurrent,
  loadHistoryForContext,
  markReadForContext,
  type ChatContext,
} from '../lib/chatContext'

/**
 * B 端客户沟通工作台（P4）：左会话列表 / 右聊天区，独立滚动，输入条吸底。
 *
 * 与 C 端聊天页共用同一套会话/消息数据与 P2.1 游标语义（去重按 id、BigInt 比较）：
 *  - 选中会话 = GET 详情（刷新/深链恢复，不依赖列表内存）+ 最近一页（DESC→反转 ASC）；
 *  - 增量：9s 轮询 afterId（ASC 追加；hasMore 以本页末条续拉直到补齐）；隐藏停轮询、回前台补拉；
 *  - 发送：clientMsgId 一次逻辑发送一次，失败重试复用原键；发送人姓名由服务端按员工资料解析；
 *  - 已读：门店级共享已读——仅可见 + 确有新消息时调 read（快照幂等）。
 *
 * 门店切换（storeContext）：旧店列表/选中/消息/派生未读**全部清空**并重载；在途响应以 seq 守卫
 * 丢弃（旧店数据绝不残留在新店视图）。URL ?open= 仅作导航恢复提示，真实权限一律由服务端裁决。
 */

const POLL_MS = 9_000
const PAGE = 20
/** H1 搜索防抖：逐字输入暂停 300ms 后才发服务端全量搜索请求（竞态由 listSeq 丢弃旧响应）。 */
const SEARCH_DEBOUNCE_MS = 300
const CONTENT_MAX = 500
const ID_RE = /^\d{1,19}$/

interface PendingMsg {
  tmpId: string
  clientMsgId: string
  content: string
  /** sending/failed=发送段；uploading/upload-failed=图片上传段（P7.2 分段重试，互不串扰） */
  status: 'sending' | 'failed' | 'uploading' | 'upload-failed'
  /** 仅图片：本地 blob 预览——只存在 pending 临时态，绝不写进消息对象 */
  localUrl?: string
  /** 仅图片：PUT 成功后的对象 key（send 重试复用，**不重传**） */
  mediaKey?: string
  /** 仅图片：原始文件（上传段失败时重传用） */
  file?: File
}

export default function CustomerChatPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { push } = useToast()
  const ctx = useSyncExternalStore(storeContext.subscribe, storeContext.get)
  const storeId = ctx.storeId
  /** storeContext 真实就绪（与 api client 放行条件同源：phase==='ready' 时 storeId 必非空，P7.3） */
  const ctxReady = ctx.phase === 'ready'
  const openParam = searchParams.get('open')

  // ---- 会话列表 ----
  const [list, setList] = useState<StoreConversationVO[] | null>(null)
  const [listTotal, setListTotal] = useState(0)
  const [listPage, setListPage] = useState(1)
  const [listError, setListError] = useState<string | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const [q, setQ] = useState('')
  const [unreadOnly, setUnreadOnly] = useState(false)

  // ---- 选中会话与聊天区 ----
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [conv, setConv] = useState<StoreConversationVO | null>(null)
  const [messages, setMessages] = useState<StoreChatMessageVO[]>([])
  const [hasMoreBefore, setHasMoreBefore] = useState(false)
  const [loadingBefore, setLoadingBefore] = useState(false)
  const [chatState, setChatState] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const [chatError, setChatError] = useState<{ code: number; message: string } | null>(null)
  const [draft, setDraft] = useState('')
  const [pending, setPending] = useState<PendingMsg[]>([])
  const [hasNewBelow, setHasNewBelow] = useState(false)
  /** 图片大图预览（只消费服务端 mediaUrl 或 pending 本地 blob，绝不拼 URL） */
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)

  const listSeq = useRef(0)
  /** H1：loadList 在防抖回调/事件处理器里读取"当前"筛选条件（state 的同步镜像，不等 re-render）。 */
  const qRef = useRef('')
  const unreadRef = useRef(false)
  const searchTimerRef = useRef<number | null>(null)
  const chatSeq = useRef(0)
  const listRef = useRef<HTMLDivElement>(null)
  const endRef = useRef<HTMLDivElement>(null)
  const imageInputRef = useRef<HTMLInputElement>(null)
  /**
   * P7.3：深链 ?open= 单飞。记录"已发起的 openParam + 发起当时的 chatSeq"：
   *  - 同参数且发起后 chatSeq 未推进 → 重复（ctxReady 过渡重跑 / StrictMode 二次执行）→ 跳过；
   *  - 若在途发起已被清场作废（chatSeq 推进：换店清场 effect 或 StrictMode mount 双跑的第二次
   *    setup 都会推进它）→ 允许补发一次，与修复前"双跑各发一次、后者生效"等价（否则请求
   *    因 seq 过期被丢弃后将无人重试——SPA 导航路径曾因此打不开会话）。
   * 生产（无 StrictMode）下，这两条只在 ctxReady 过渡重跑时起作用。
   */
  const openedForRef = useRef<{ param: string; atSeq: number } | null>(null)
  const aliveRef = useRef(true)
  const atBottomRef = useRef(true)
  const latestIdRef = useRef<string | null>(null)
  const readMarkedRef = useRef<string | null>(null)
  const messagesRef = useRef<StoreChatMessageVO[]>([])
  const storeIdRef = useRef<string | null>(storeId)
  const chatCtxRef = useRef<ChatContext | null>(null)
  const chatAbortRef = useRef<AbortController | null>(null)
  /** 单飞按上下文隔离：旧会话/旧门店的 tick 不能清掉新上下文的在途标记。 */
  const pollingCtxRef = useRef<ChatContext | null>(null)
  /** 供空依赖 useCallback（syncRead/deliver）读取最新选中会话 */
  const selectedIdRef = useRef<string | null>(null)
  selectedIdRef.current = selectedId
  storeIdRef.current = storeId
  qRef.current = q
  unreadRef.current = unreadOnly

  /** 作废当前会话/门店上下文并取消其全部在途请求。 */
  const invalidateChatContext = useCallback(() => {
    chatSeq.current += 1
    chatCtxRef.current = null
    chatAbortRef.current?.abort()
    chatAbortRef.current = null
    pollingCtxRef.current = null
  }, [])

  /** 新会话上下文：seq 复用 chatSeq（深链单飞也以此判断在途请求是否已被清场）。 */
  const beginChatContext = useCallback((conversationId: string): ChatContext => {
    chatAbortRef.current?.abort()
    const ac = new AbortController()
    chatAbortRef.current = ac
    const seq = ++chatSeq.current
    const ctx: ChatContext = { conversationId, storeId: storeIdRef.current, seq }
    chatCtxRef.current = ctx
    return ctx
  }, [])

  /** 门店 state 已变、清场 effect 尚未执行的微任务窗口里，也必须让旧店上下文立即失效。 */
  const isChatCurrent = useCallback(
    (ctx: ChatContext) =>
      ctx.storeId === storeIdRef.current &&
      isChatContextCurrent(chatCtxRef.current, ctx, aliveRef.current),
    [],
  )

  /* ---------------- 已读（可见 + 确有新消息才调；成功后本地行清零 + 铃铛刷新） ---------------- */
  const syncRead = useCallback(async (ctx: ChatContext) => {
    const signal = chatAbortRef.current?.signal
    const result = await markReadForContext(
      ctx,
      () => isChatCurrent(ctx),
      latestIdRef.current,
      readMarkedRef.current,
      document.visibilityState === 'visible',
      (target) => chatApi.markRead(target.conversationId, signal),
    )
    if (result.status !== 'ok') return
    const target = result.value
    if (!readMarkedRef.current || cmpId(target.latestId, readMarkedRef.current) > 0) {
      readMarkedRef.current = target.latestId
    }
    setList((prev) =>
      prev?.map((c) => (c.id === target.conversationId ? { ...c, unread: 0 } : c)) ?? prev,
    )
    void refreshUnreadCount()
  }, [isChatCurrent])

  /* ---------------- 列表加载（seq 守卫 + 换店清场 + H1 服务端筛选） ---------------- */
  /**
   * @param append 追加下一页（加载更多）；false=整页替换
   * @param opts.silent 静默替换：不清空旧列表再填充（筛选切换/退出搜索避免骨架闪烁）；换店/首次加载不用
   * 关键字与"仅看未读"从 ref 读取（防抖回调触发时也拿得到最新值）；过滤与分页都在服务端完成。
   */
  const loadList = useCallback(
    async (page = 1, append = false, opts: { silent?: boolean } = {}) => {
      if (!storeId) return
      const myStore = storeId
      const kw = qRef.current.trim()
      const my = ++listSeq.current
      if (!append) {
        setListError(null)
        if (!opts.silent) setList(null)
      } else {
        setLoadingMore(true)
      }
      try {
        const p = await chatApi.conversations(page, PAGE, {
          keyword: kw || undefined,
          unreadOnly: unreadRef.current,
        })
        if (my !== listSeq.current || myStore !== storeIdRef.current || !aliveRef.current) return // 旧店/旧页/旧筛选响应：丢弃
        setList((prev) => (append ? [...(prev ?? []), ...(p.list ?? [])] : (p.list ?? [])))
        setListTotal(Number(p.total ?? 0))
        setListPage(page)
      } catch (e) {
        if (my !== listSeq.current || myStore !== storeIdRef.current || !aliveRef.current) return
        if (!append) {
          setList(null)
          setListError(e instanceof BizError ? e.message : '会话列表读取失败，请稍后重试')
        } else {
          push(e instanceof BizError ? e.message : '加载更多失败', 'error')
        }
      } finally {
        if (append && my === listSeq.current && myStore === storeIdRef.current && aliveRef.current) {
          setLoadingMore(false)
        }
      }
    },
    [storeId, push],
  )

  /**
   * H1 搜索输入（服务端全量搜索）：
   *  - 非空：300ms 防抖后请求服务端——keyword 在**本店全量会话**上过滤、过滤后再分页；
   *    逐字变化只重置防抖窗口，竞态由 loadList 的 listSeq 丢弃旧响应；
   *  - 清空：立即静默恢复当前（仅看未读可能仍开启）第 1 页——不清空旧列表故无骨架闪烁。
   */
  const clearSearchTimer = () => {
    if (searchTimerRef.current !== null) {
      window.clearTimeout(searchTimerRef.current)
      searchTimerRef.current = null
    }
  }
  const onSearchChange = (value: string) => {
    setQ(value)
    qRef.current = value // 防抖回调读 ref：先同步镜像，不等 re-render
    clearSearchTimer()
    if (!value.trim()) {
      void loadList(1, false, { silent: true })
      return
    }
    searchTimerRef.current = window.setTimeout(() => {
      searchTimerRef.current = null
      void loadList(1, false, { silent: true })
    }, SEARCH_DEBOUNCE_MS)
  }
  /** 仅看未读：服务端过滤（与 keyword 同层），切换即重取第 1 页。 */
  const onToggleUnreadOnly = () => {
    const next = !unreadRef.current
    unreadRef.current = next
    setUnreadOnly(next)
    clearSearchTimer()
    void loadList(1, false, { silent: true })
  }

  /** 换店：清空旧店一切派生态（列表/选中/消息/未读）后重载——旧店数据绝不残留在新店视图。 */
  useEffect(() => {
    aliveRef.current = true
    invalidateChatContext()
    setList(null)
    setListTotal(0)
    setListPage(1)
    setListError(null)
    setLoadingMore(false)
    setSelectedId(null)
    selectedIdRef.current = null
    setConv(null)
    messagesRef.current = []
    setMessages([])
    setLoadingBefore(false)
    setChatState('idle')
    setChatError(null)
    setPending((prev) => {
      for (const p of prev) if (p.localUrl) URL.revokeObjectURL(p.localUrl) // 换店丢弃本地预览
      return []
    })
    setDraft('')
    setHasNewBelow(false)
    setPreviewUrl(null)
    setQ('') // 搜索词/筛选随门店一起清场：换店后不残留旧店的过滤条件
    setUnreadOnly(false)
    qRef.current = ''
    unreadRef.current = false
    clearSearchTimer()
    latestIdRef.current = null
    readMarkedRef.current = null
    listSeq.current += 1 // 作废在途列表请求
    if (storeId) void loadList(1, false)
    return () => {
      aliveRef.current = false
      chatAbortRef.current?.abort()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storeId])

  /* ---------------- 选中会话（详情 + 最近一页；seq 守卫防旧会话覆盖新会话） ---------------- */
  const selectConversation = useCallback(
    async (id: string) => {
      setSelectedId(id)
      selectedIdRef.current = id
      setSearchParams({ open: id }, { replace: true })
      if (!ID_RE.test(id)) {
        invalidateChatContext()
        setChatState('error')
        setChatError({ code: 10001, message: '会话地址不正确' })
        return
      }
      const ctx = beginChatContext(id)
      const signal = chatAbortRef.current?.signal
      setChatState('loading')
      setConv(null)
      messagesRef.current = []
      setMessages([])
      setLoadingBefore(false)
      setPending((prev) => {
        for (const p of prev) if (p.localUrl) URL.revokeObjectURL(p.localUrl) // 切会话丢弃本地预览
        return []
      })
      setDraft('')
      setHasNewBelow(false)
      setPreviewUrl(null)
      latestIdRef.current = null
      readMarkedRef.current = null
      try {
        const [c, page] = await Promise.all([
          chatApi.conversation(id, signal),
          chatApi.messages(id, { limit: PAGE }, signal),
        ])
        if (!isChatCurrent(ctx)) return
        const asc = [...(page.list ?? [])].reverse()
        setConv(c)
        messagesRef.current = asc
        setMessages(asc)
        setHasMoreBefore(page.hasMore)
        latestIdRef.current = asc.length ? String(asc[asc.length - 1].id) : null
        setChatState('ready')
        requestAnimationFrame(() => endRef.current?.scrollIntoView({ block: 'end' }))
        void syncRead(ctx)
      } catch (e) {
        if (!isChatCurrent(ctx)) return
        const biz = e instanceof BizError ? e : null
        setChatState('error')
        setChatError({ code: biz?.code ?? -1, message: biz ? biz.message : '会话加载失败，请稍后重试' })
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [setSearchParams, beginChatContext, invalidateChatContext, syncRead, isChatCurrent],
  )

  /**
   * 深链 ?open=：工作台打开/刷新（F5）后自动定位目标会话（真实鉴权由服务端裁决）。
   *
   * P7.3 冷加载修复：本页 effect 属页面自身（Layout 是它渲染的子级），Layout 的 BootScreen
   * 门禁**挡不住页面 effect**——冷加载时本 effect 曾在 storeContext 未就绪时先跑一次，
   * 请求被 api client 在 fetch 前以 NO_STORE_CTX 拦下，此后 openParam 不变、无人重试。
   * 修复：
   *  - 以 **ctxReady（phase==='ready'，此时 storeId 必非空）** 为执行门：未就绪不发任何请求；
   *  - openedForRef（param+chatSeq 快照）保证同一 openParam 不重复打开，同时允许对被
   *    seq 清场作废的在途发起补发一次（见 ref 注释）；
   *  - 换店不重置该 ref：?open= 不因切店二次打开（与既有安全语义一致，换店清场 effect 照旧兜底）。
   */
  useEffect(() => {
    if (!openParam || !ctxReady) return
    const prev = openedForRef.current
    if (prev && prev.param === openParam && prev.atSeq === chatSeq.current) return
    openedForRef.current = { param: openParam, atSeq: chatSeq.current }
    if (openParam !== selectedIdRef.current) void selectConversation(openParam)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openParam, ctxReady])

  /* ---------------- 增量合并（afterId → ASC 到达） ---------------- */
  const applyIncoming = (ctx: ChatContext, incomingAsc: StoreChatMessageVO[]) => {
    if (!isChatCurrent(ctx)) return
    const commit = commitIncoming(
      ctx,
      chatCtxRef.current,
      aliveRef.current,
      messagesRef.current,
      latestIdRef.current,
      incomingAsc,
    )
    if (!commit.applied) return
    messagesRef.current = commit.messages
    setMessages(commit.messages)
    latestIdRef.current = commit.latestId
    // 列表摘要只跟随真正推进了游标的响应；迟到旧响应不能把列表时间/预览拉回去。
    if (commit.advanced) {
      const last = incomingAsc[incomingAsc.length - 1]
      setList((prev) => {
        if (!prev || !isChatCurrent(ctx)) return prev
        const next = prev.map((c) =>
          c.id === ctx.conversationId
            ? { ...c, lastMessagePreview: last.content.slice(0, 100), lastMessageAt: last.createdAt }
            : c,
        )
        return [...next].sort((a, b) => ((a.lastMessageAt ?? '') < (b.lastMessageAt ?? '') ? 1 : -1))
      })
      if (atBottomRef.current) {
        requestAnimationFrame(() => endRef.current?.scrollIntoView({ block: 'end', behavior: 'smooth' }))
      } else if (incomingAsc.some((m) => m.senderType === 'CUSTOMER')) {
        setHasNewBelow(true)
      }
    }
    void syncRead(ctx)
  }

  /* ---------------- 轮询（9s；单飞；可见性；hasMore 补齐；失败保现状） ---------------- */
  useEffect(() => {
    if (chatState !== 'ready' || !selectedId) return
    const ctx = chatCtxRef.current
    if (!ctx) return
    const signal = chatAbortRef.current?.signal
    const tick = async () => {
      if (document.visibilityState !== 'visible' || !isChatCurrent(ctx)) return
      const inflight = pollingCtxRef.current
      if (inflight && isChatCurrent(inflight)) return
      pollingCtxRef.current = ctx
      try {
        let cursor = latestIdRef.current
        if (!cursor) {
          const page = await chatApi.messages(ctx.conversationId, { limit: PAGE }, signal)
          if (!isChatCurrent(ctx)) return
          applyIncoming(ctx, page.list ?? [])
        } else {
          let page = await chatApi.messages(ctx.conversationId, { afterId: cursor, limit: 50 }, signal)
          if (!isChatCurrent(ctx)) return
          applyIncoming(ctx, page.list ?? [])
          let more = page.hasMore
          while (more) {
            if (!isChatCurrent(ctx)) break
            const last = page.list?.[page.list.length - 1]
            if (!last) break
            page = await chatApi.messages(ctx.conversationId, { afterId: String(last.id), limit: 50 }, signal)
            if (!isChatCurrent(ctx)) return
            applyIncoming(ctx, page.list ?? [])
            more = page.hasMore
          }
        }
      } catch {
        // 轮询失败保留已加载消息；下个周期/回前台自动重试
      } finally {
        if (pollingCtxRef.current === ctx) pollingCtxRef.current = null
      }
    }
    const timer = window.setInterval(() => void tick(), POLL_MS)
    const onVis = () => {
      if (document.visibilityState === 'visible') void tick()
    }
    document.addEventListener('visibilitychange', onVis)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVis)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chatState, selectedId])

  /* ---------------- 加载更早（beforeId；按钮置顶 + 插入在下方，滚动交给原生 anchoring） ---------------- */
  const loadBefore = async () => {
    const ctx = chatCtxRef.current
    if (!ctx || loadingBefore || messagesRef.current.length === 0 || !isChatCurrent(ctx)) return
    const signal = chatAbortRef.current?.signal
    setLoadingBefore(true)
    try {
      const oldest = String(messagesRef.current[0].id)
      const result = await loadHistoryForContext(
        ctx,
        () => isChatCurrent(ctx),
        (requestCtx) => chatApi.messages(requestCtx.conversationId, { beforeId: oldest, limit: PAGE }, signal),
        () => messagesRef.current,
      )
      if (result.status === 'ok') {
        messagesRef.current = result.value.messages
        setMessages(result.value.messages)
        setHasMoreBefore(result.value.hasMore)
      } else if (result.status === 'error') {
        push(result.error instanceof BizError ? result.error.message : '加载更早消息失败', 'error')
      }
    } finally {
      if (isChatCurrent(ctx)) setLoadingBefore(false)
    }
  }

  /* ---------------- 发送（clientMsgId 幂等 + 失败重试复用原键） ---------------- */
  const deliver = async (
    ctx: ChatContext,
    signal: AbortSignal | undefined,
    p: { tmpId: string; clientMsgId: string; content: string },
  ) => {
    if (!isChatCurrent(ctx)) return
    try {
      const real = await chatApi.send(ctx.conversationId, { content: p.content, clientMsgId: p.clientMsgId }, signal)
      if (!isChatCurrent(ctx)) return
      setPending((prev) => prev.filter((x) => x.tmpId !== p.tmpId))
      applyIncoming(ctx, [real])
    } catch (e) {
      if (!isChatCurrent(ctx)) return
      setPending((prev) => prev.map((x) => (x.tmpId === p.tmpId ? { ...x, status: 'failed' } : x)))
      push(e instanceof BizError ? e.message : '发送失败，请重试', 'error')
    }
  }

  const send = async () => {
    const text = draft.trim()
    const ctx = chatCtxRef.current
    if (!text || !ctx || !isChatCurrent(ctx)) return
    if (text.length > CONTENT_MAX) {
      push(`消息不超过 ${CONTENT_MAX} 字`, 'info')
      return
    }
    const signal = chatAbortRef.current?.signal
    const clientMsgId = newClientMsgId()
    const tmpId = `tmp-${clientMsgId}`
    setPending((prev) => [...prev, { tmpId, clientMsgId, content: text, status: 'sending' }])
    setDraft('')
    atBottomRef.current = true
    requestAnimationFrame(() => endRef.current?.scrollIntoView({ block: 'end' }))
    await deliver(ctx, signal, { tmpId, clientMsgId, content: text })
  }

  const retry = (p: PendingMsg) => {
    const ctx = chatCtxRef.current
    if (!ctx || !isChatCurrent(ctx)) return
    const signal = chatAbortRef.current?.signal
    if (p.mediaKey) {
      // 图片：PUT 已成功、仅发送失败 → 复用原 clientMsgId + mediaKey 重发（不重传）
      setPending((prev) => prev.map((x) => (x.tmpId === p.tmpId ? { ...x, status: 'sending' } : x)))
      void deliverImageSend(ctx, signal, p.tmpId, p.clientMsgId, p.mediaKey)
      return
    }
    if (p.file) {
      // 图片：上传段失败 → 重传（新 key；消息幂等仍由 clientMsgId 兜底）
      setPending((prev) => prev.map((x) => (x.tmpId === p.tmpId ? { ...x, status: 'uploading' } : x)))
      void deliverImage(ctx, signal, p)
      return
    }
    setPending((prev) => prev.map((x) => (x.tmpId === p.tmpId ? { ...x, status: 'sending' } : x)))
    void deliver(ctx, signal, { tmpId: p.tmpId, clientMsgId: p.clientMsgId, content: p.content }) // 复用原 clientMsgId
  }

  /* ---------------- 图片发送（P7.2：intent → presigned PUT → IMAGE 消息；两段失败各自可重试） ---------------- */

  const deliverImageSend = async (
    ctx: ChatContext,
    signal: AbortSignal | undefined,
    tmpId: string,
    clientMsgId: string,
    mediaKey: string,
  ) => {
    if (!isChatCurrent(ctx)) return
    try {
      const real = await chatApi.send(ctx.conversationId, {
        content: '[图片]',
        messageType: 'IMAGE',
        mediaKey,
        clientMsgId, // 与首次完全一致：服务端 uk 幂等，超时重发不产生第二条
      }, signal)
      if (!isChatCurrent(ctx)) return
      setPending((prev) => {
        const gone = prev.find((x) => x.tmpId === tmpId)
        if (gone?.localUrl) URL.revokeObjectURL(gone.localUrl) // 真实消息已合并，本地预览功成身退
        return prev.filter((x) => x.tmpId !== tmpId)
      })
      applyIncoming(ctx, [real])
    } catch (e) {
      if (!isChatCurrent(ctx)) return
      setPending((prev) => prev.map((x) => (x.tmpId === tmpId ? { ...x, status: 'failed' } : x)))
      push(e instanceof BizError ? e.message : '发送失败，请重试', 'error')
    }
  }

  const deliverImage = async (ctx: ChatContext, signal: AbortSignal | undefined, p: PendingMsg) => {
    if (!p.file || !isChatCurrent(ctx)) return
    try {
      // intent 走统一 http 封装（自动注入 X-Store-Id）；会话归属由服务端校验（10003/10004）
      const key = await uploadImage(p.file, 'CHAT_IMAGE', ctx.conversationId, signal)
      if (!isChatCurrent(ctx)) return
      setPending((prev) => prev.map((x) => (x.tmpId === p.tmpId ? { ...x, status: 'sending', mediaKey: key } : x)))
      await deliverImageSend(ctx, signal, p.tmpId, p.clientMsgId, key)
    } catch (e) {
      if (!isChatCurrent(ctx)) return
      setPending((prev) => prev.map((x) => (x.tmpId === p.tmpId ? { ...x, status: 'upload-failed' } : x)))
      push(e instanceof UploadError ? e.message : '图片上传失败，请重试', 'error')
    }
  }

  const pickImage = (file: File | undefined) => {
    if (!file) return
    const ctx = chatCtxRef.current
    if (!ctx || !isChatCurrent(ctx)) return
    const bad = validateImage(file) // 前端预检（MIME/≤5MB）；服务端另有权威校验
    if (bad) {
      push(bad, 'info')
      return
    }
    const signal = chatAbortRef.current?.signal
    const clientMsgId = newClientMsgId() // 一次逻辑发送一个键；上传/发送两段重试都复用它
    const tmpId = `tmp-${clientMsgId}`
    const localUrl = URL.createObjectURL(file)
    const entry: PendingMsg = { tmpId, clientMsgId, content: '[图片]', status: 'uploading', localUrl, file }
    setPending((prev) => [...prev, entry])
    atBottomRef.current = true
    requestAnimationFrame(() => endRef.current?.scrollIntoView({ block: 'end' }))
    void deliverImage(ctx, signal, entry)
  }

  const onScroll = () => {
    const el = listRef.current
    if (!el) return
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80
    atBottomRef.current = nearBottom
    if (nearBottom) setHasNewBelow(false)
  }

  const listHasMore = list !== null && list.length < listTotal
  const filtered = !!q.trim() || unreadOnly

  return (
    <Layout active="chat">
      <div className="flex h-[calc(100dvh-4rem)]">
        {/* ---------- 左栏：会话列表 ---------- */}
        <aside className="flex w-[320px] shrink-0 flex-col border-r border-line bg-white">
          <div className="space-y-2 border-b border-line p-3">
            <label className="relative block">
              <IconSearch className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
              <input
                value={q}
                onChange={(e) => onSearchChange(e.target.value)}
                placeholder="搜索客户姓名 / 手机尾号"
                aria-label="搜索会话"
                className="h-9 w-full rounded-lg bg-blue-mist pl-9 pr-3 text-xs text-ink placeholder:text-muted focus:outline-none focus:ring-1 focus:ring-blue"
              />
            </label>
            <div className="flex items-center justify-between">
              <button
                type="button"
                aria-pressed={unreadOnly}
                onClick={onToggleUnreadOnly}
                className={cx(
                  'h-7 rounded-full px-3 text-[11px] font-semibold transition',
                  unreadOnly ? 'bg-blue text-white' : 'bg-blue-mist text-muted hover:text-ink',
                )}
              >
                仅看未读
              </button>
              <span className="text-[11px] text-muted">共 {listTotal} 个会话</span>
            </div>
          </div>

          <div className="flex-1 overflow-y-auto p-2">
            {listError && (
              <div className="m-2 rounded-xl bg-white p-4 text-center ring-1 ring-rose-200">
                <p className="text-xs font-bold text-rose-700">会话列表没读出来</p>
                <p className="mt-1 text-[11px] text-rose-600/90">{listError}</p>
                <button
                  type="button"
                  onClick={() => void loadList(1, false)}
                  className="mt-3 h-8 rounded-lg bg-rose-600 px-3 text-[11px] font-bold text-white"
                >
                  重试
                </button>
              </div>
            )}

            {!listError && list === null && (
              <div className="space-y-2 p-1" aria-busy="true">
                <div className="h-16 animate-pulse rounded-xl bg-blue-mist" />
                <div className="h-16 animate-pulse rounded-xl bg-blue-mist" />
                <div className="h-16 animate-pulse rounded-xl bg-blue-mist" />
              </div>
            )}

            {!listError && list !== null && list.length === 0 && !filtered && (
              <div className="m-2 rounded-xl bg-blue-mist/70 p-5 text-center">
                <IconChat className="mx-auto h-7 w-7 text-blue/50" />
                <p className="mt-2 text-xs font-bold text-ink">暂时还没有客户沟通记录</p>
                <p className="mt-1 text-[11px] leading-relaxed text-muted">
                  当客户发来消息，或从客户管理发起沟通后，会话会显示在这里。
                </p>
              </div>
            )}

            {!listError && list !== null && list.length === 0 && filtered && (
              <div className="m-3 rounded-xl bg-blue-mist px-3 py-3 text-center">
                <p className="text-[11px] font-semibold text-muted">未找到匹配客户</p>
                <p className="mt-1 text-[10px] leading-relaxed text-muted/80">换个姓名或手机号试试。</p>
              </div>
            )}

            {!listError &&
              list?.map((c) => (
                <ConversationRow
                  key={c.id}
                  conv={c}
                  selected={c.id === selectedId}
                  onSelect={() => void selectConversation(c.id)}
                />
              ))}

            {/* H1：搜索/筛选结果同样支持"加载更多"（分页发生在服务端过滤之后） */}
            {!listError && listHasMore && (
              <button
                type="button"
                onClick={() => void loadList(listPage + 1, true)}
                disabled={loadingMore}
                className="mt-2 w-full rounded-lg bg-blue-mist py-2 text-[11px] font-bold text-blue transition hover:bg-blue-soft disabled:opacity-60"
              >
                {loadingMore ? '加载中…' : '加载更多会话'}
              </button>
            )}
          </div>
        </aside>

        {/* ---------- 右栏：聊天区 ---------- */}
        <section className="flex min-w-0 flex-1 flex-col bg-blue-mist/40">
          {chatState === 'idle' && (
            <div className="grid flex-1 place-items-center p-8">
              <div className="max-w-sm text-center">
                <IconChat className="mx-auto h-10 w-10 text-blue/40" />
                <p className="mt-3 text-sm font-bold text-ink">选择左侧会话开始沟通</p>
                <p className="mt-1 text-xs leading-relaxed text-muted">
                  也可以从「客户管理」的客户详情点「发送消息」，直接定位到对应会话。
                </p>
              </div>
            </div>
          )}

          {chatState === 'loading' && (
            <div className="flex-1 space-y-3 p-6" aria-busy="true">
              <div className="h-12 animate-pulse rounded-xl bg-white/70" />
              <div className="h-40 animate-pulse rounded-xl bg-white/70" />
            </div>
          )}

          {chatState === 'error' && (
            <div className="grid flex-1 place-items-center p-8">
              <div className="w-full max-w-sm rounded-2xl bg-white p-6 text-center shadow-soft ring-1 ring-line">
                <p className="text-sm font-bold text-ink">
                  {chatError?.code === 10004 || chatError?.code === 10003 ? '会话不存在或无权查看' : '会话没打开'}
                </p>
                <p className="mt-1 text-xs leading-relaxed text-muted">
                  {chatError?.code === 10004 || chatError?.code === 10003
                    ? '该会话不属于当前门店，或链接已失效（切换门店后请从左侧列表重新选择）。'
                    : (chatError?.message ?? '请稍后重试')}
                </p>
                <button
                  type="button"
                  onClick={() => selectedId && void selectConversation(selectedId)}
                  className="mt-4 h-9 rounded-xl bg-blue px-4 text-xs font-bold text-white transition hover:bg-blue-deep"
                >
                  重试
                </button>
              </div>
            </div>
          )}

          {chatState === 'ready' && conv && (
            <>
              {/* 会话头 */}
              <header className="flex items-center justify-between gap-3 border-b border-line bg-white px-5 py-3">
                <div className="min-w-0">
                  <p className="flex items-center gap-2">
                    <span className="truncate text-sm font-black text-ink">{conv.customerName}</span>
                    <span className="shrink-0 font-mono text-[11px] text-muted">{conv.phoneMasked}</span>
                  </p>
                  <p className="mt-0.5 text-[11px] text-muted">
                    {conv.originBizModule === 'appointment' && conv.originBizId
                      ? `首次沟通来自预约单 · 会话创建于 ${conv.lastMessageAt ? timeAgo(conv.lastMessageAt) : '—'}`
                      : '门店与客户的长期会话（同一客户复用同一条）'}
                  </p>
                </div>
              </header>

              {/* 消息区 */}
              <div ref={listRef} onScroll={onScroll} className="flex-1 overflow-y-auto px-5 py-3">
                {hasMoreBefore && (
                  <button
                    type="button"
                    onClick={() => void loadBefore()}
                    disabled={loadingBefore}
                    className="mx-auto mb-3 block rounded-full bg-white px-4 py-1.5 text-[11px] font-semibold text-blue ring-1 ring-line transition hover:bg-blue-mist disabled:opacity-60"
                  >
                    {loadingBefore ? '加载中…' : '加载更早的消息'}
                  </button>
                )}

                {messages.length === 0 && pending.length === 0 && (
                  <p className="mt-10 text-center text-xs text-muted">
                    该会话还没有消息——发送第一条，或等客户发来消息。
                  </p>
                )}

                {messages.map((m, i) => (
                  <Bubble key={String(m.id)} msg={m} prev={messages[i - 1]} onPreview={setPreviewUrl} />
                ))}
                {pending.map((p) => (
                  <PendingBubble key={p.tmpId} p={p} onRetry={() => retry(p)} />
                ))}
                <div ref={endRef} />
              </div>

              {hasNewBelow && (
                <button
                  type="button"
                  onClick={() => {
                    setHasNewBelow(false)
                    atBottomRef.current = true
                    endRef.current?.scrollIntoView({ block: 'end', behavior: 'smooth' })
                  }}
                  className="mx-auto -mt-9 mb-1 block rounded-full bg-blue px-4 py-1.5 text-[11px] font-bold text-white shadow-btn"
                >
                  有新消息 ↓
                </button>
              )}

              {/* 输入条 */}
              <div className="border-t border-line bg-white px-4 py-3">
                <div className="flex items-end gap-2.5">
                  <button
                    type="button"
                    onClick={() => imageInputRef.current?.click()}
                    aria-label="发送图片"
                    className="grid h-[52px] w-[52px] shrink-0 place-items-center rounded-xl bg-blue-mist/70 text-ink/60 transition hover:bg-blue-soft"
                  >
                    <IconImage className="h-5 w-5" />
                  </button>
                  <input
                    ref={imageInputRef}
                    type="file"
                    accept="image/jpeg,image/png,image/webp"
                    aria-label="选择图片"
                    className="sr-only"
                    onChange={(e) => {
                      const f = e.target.files?.[0]
                      e.target.value = '' // 复位：同一张图可再次选择
                      pickImage(f)
                    }}
                  />
                  <textarea
                    value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && !e.shiftKey) {
                        e.preventDefault()
                        void send()
                      }
                    }}
                    rows={2}
                    maxLength={CONTENT_MAX + 20}
                    placeholder="输入消息，Enter 发送 / Shift+Enter 换行"
                    aria-label="消息输入框"
                    className="max-h-36 min-h-[52px] flex-1 resize-none rounded-xl bg-blue-mist/70 px-3.5 py-2.5 text-sm leading-relaxed text-ink placeholder:text-muted focus:outline-none focus:ring-1 focus:ring-blue"
                  />
                  <button
                    type="button"
                    onClick={() => void send()}
                    disabled={!draft.trim()}
                    className={cx(
                      'h-[52px] shrink-0 rounded-xl px-5 text-sm font-bold text-white transition',
                      draft.trim() ? 'bg-blue hover:bg-blue-deep' : 'bg-blue/30',
                    )}
                  >
                    发送
                  </button>
                </div>
                <p className="mt-1.5 flex items-center justify-between text-[10px] text-muted">
                  <span>Enter 发送，Shift+Enter 换行{draft.length > CONTENT_MAX ? '（内容超出 500 字上限）' : ''}</span>
                  <span>
                    {draft.length}/{CONTENT_MAX}
                  </span>
                </p>
              </div>
            </>
          )}
        </section>
      </div>

      {/* 图片大图预览：只消费服务端 mediaUrl（ChatGuard 之后的签名出口）或 pending 本地 blob */}
      <Modal open={!!previewUrl} title="图片预览" width="max-w-[760px]" onClose={() => setPreviewUrl(null)}>
        {previewUrl && (
          <img src={previewUrl} alt="图片预览" className="mx-auto max-h-[70vh] w-auto rounded-xl object-contain" />
        )}
      </Modal>
    </Layout>
  )
}

/* ---------------- 会话行 ---------------- */
function ConversationRow({
  conv: c,
  selected,
  onSelect,
}: {
  conv: StoreConversationVO
  selected: boolean
  onSelect: () => void
}) {
  const n = Number(c.unread)
  const unread = Number.isFinite(n) && n > 0 ? n : 0
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      aria-label={`${c.customerName}${unread > 0 ? `（${unread} 条未读）` : ''}`}
      className={cx(
        'mb-1.5 w-full rounded-xl p-3 text-left transition',
        selected ? 'bg-blue-soft ring-1 ring-blue/30' : 'hover:bg-blue-mist',
      )}
    >
      <div className="flex items-center gap-2">
        <span className="min-w-0 flex-1 truncate text-[13px] font-bold text-ink">{c.customerName}</span>
        <span className="shrink-0 font-mono text-[10px] text-muted">{c.phoneMasked}</span>
        {unread > 0 && (
          <span className="grid h-[16px] min-w-[16px] shrink-0 place-items-center rounded-full bg-[#E8506B] px-1 text-[10px] font-bold text-white">
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </div>
      <p className={cx('mt-1 truncate text-[11px]', unread > 0 ? 'font-semibold text-ink/80' : 'text-muted')}>
        {c.lastMessagePreview || '暂无消息'}
      </p>
      <p className="mt-0.5 text-[10px] text-muted/70">{c.lastMessageAt ? timeAgo(c.lastMessageAt) : ''}</p>
    </button>
  )
}

/* ---------------- 气泡 ---------------- */

const hmOf = (iso: string) => (iso.length >= 16 ? iso.slice(11, 16) : '')
const dayOf = (iso: string) => iso.slice(0, 10)

function Bubble({ msg, prev, onPreview }: { msg: StoreChatMessageVO; prev?: StoreChatMessageVO; onPreview: (url: string) => void }) {
  const mine = msg.senderType === 'STORE'
  const showDay = !prev || dayOf(prev.createdAt) !== dayOf(msg.createdAt)
  return (
    <>
      {showDay && <p className="my-3 text-center text-[10px] text-muted/70">{dayOf(msg.createdAt)}</p>}
      <div className={cx('mb-2.5 flex', mine ? 'justify-end' : 'justify-start')}>
        <div className="max-w-[72%]">
          <p className={cx('mb-0.5 px-1 text-[10px] text-muted', mine ? 'text-right' : 'text-left')}>
            {mine ? msg.senderName || '门店' : msg.senderName || '客户'}
          </p>
          {msg.messageType === 'IMAGE' ? (
            <ImageContent url={msg.mediaUrl} onPreview={onPreview} />
          ) : (
            <div
              className={cx(
                'whitespace-pre-wrap break-words rounded-2xl px-3.5 py-2.5 text-sm leading-relaxed',
                mine ? 'rounded-br-md bg-blue text-white' : 'rounded-bl-md bg-white text-ink shadow-soft ring-1 ring-line',
              )}
            >
              {msg.content}
            </div>
          )}
          <p className={cx('mt-0.5 px-1 text-[10px] text-muted/60', mine ? 'text-right' : 'text-left')}>
            {hmOf(msg.createdAt)}
          </p>
        </div>
      </div>
    </>
  )
}

/**
 * IMAGE 气泡内容（P7.2）：只消费服务端签名 mediaUrl——前端不拼 URL、不自行刷新签名；
 * mediaUrl 缺失或图片加载失败给文本占位（TTL 600s 过期的图，重新拉取消息即会重签）。
 */
function ImageContent({ url, onPreview }: { url: string | null; onPreview: (u: string) => void }) {
  const [broken, setBroken] = useState(false)
  if (!url || broken) {
    return (
      <div className="rounded-2xl bg-white px-3.5 py-2.5 text-xs leading-relaxed text-muted shadow-soft ring-1 ring-line">
        图片加载失败，稍后重新进入会话可自动刷新
      </div>
    )
  }
  return (
    <button
      type="button"
      onClick={() => onPreview(url)}
      aria-label="查看大图"
      className="block cursor-zoom-in overflow-hidden rounded-2xl shadow-soft ring-1 ring-line"
    >
      <img
        src={url}
        alt="图片消息"
        loading="lazy"
        onError={() => setBroken(true)}
        className="block max-h-[300px] max-w-[240px] min-h-[90px] min-w-[120px] object-contain"
      />
    </button>
  )
}

function PendingBubble({ p, onRetry }: { p: PendingMsg; onRetry: () => void }) {
  const isImage = !!p.localUrl
  const failed = p.status === 'failed' || p.status === 'upload-failed'
  const failLabel = p.status === 'upload-failed' ? '图片上传失败，点击重试' : '发送失败，点击重试'
  const busyLabel = p.status === 'uploading' ? '上传中…' : '发送中…'
  return (
    <div className="mb-2.5 flex justify-end">
      <div className="max-w-[72%]">
        {isImage ? (
          <div className={cx('overflow-hidden rounded-2xl ring-1', failed ? 'ring-rose-200' : 'ring-line')}>
            <img
              src={p.localUrl}
              alt="待发送图片"
              className={cx(
                'block max-h-[300px] max-w-[240px] min-h-[90px] min-w-[120px] object-contain',
                !failed && 'opacity-70',
              )}
            />
          </div>
        ) : (
          <div
            className={cx(
              'whitespace-pre-wrap break-words rounded-2xl rounded-br-md px-3.5 py-2.5 text-sm leading-relaxed',
              failed ? 'bg-rose-50 text-rose-700 ring-1 ring-rose-200' : 'bg-blue text-white opacity-70',
            )}
          >
            {p.content}
          </div>
        )}
        <p className={cx('mt-0.5 px-1 text-right text-[10px]', failed ? 'text-rose-500' : 'text-muted/70')}>
          {failed ? (
            <button type="button" onClick={onRetry} className="font-semibold underline underline-offset-2">
              {failLabel}
            </button>
          ) : (
            busyLabel
          )}
        </p>
      </div>
    </div>
  )
}
