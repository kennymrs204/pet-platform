import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { BizError } from '../api/types'
import { chatApi, newClientMsgId, type ChatConversationVO, type ChatMessageVO } from '../api/chat'
import { UploadError, uploadImage, validateImage } from '../api/upload'
import { tokenStore } from '../auth/tokenStore'
import { useBizFail } from '../auth/useBizFail'
import PageHeader from '../components/layout/PageHeader'
import { useToast } from '../components/ui/Toast'
import { IconImage, IconX } from '../components/ui/icons'
import { cx } from '../lib/cx'
import {
  cmpId,
  commitIncoming,
  isChatContextCurrent,
  loadHistoryForContext,
  markReadForContext,
  type ChatContext,
} from '../lib/chatContext'
import { dateText, todayISO } from '../lib/dateText'
import { refreshUnreadCount } from '../lib/unreadCount'

/**
 * C 端门店聊天页（P3）。独立全屏布局：无底部主导航，输入条吸底。
 *
 * 与后端 P2.1 契约逐条对齐：
 *  - 初始化：GET 会话详情（刷新恢复，不依赖列表内存）+ 最近一页消息（id DESC → 反转为旧→新的显示序）；
 *  - 翻历史：beforeId=当前最旧一条 id（DESC 返回再反转，插入列表头部），滚动位置按高度差补偿；
 *  - 增量：afterId=当前最新一条 id（**ASC 返回**按序追加），hasMore=true 时以本页末条 id 续拉直到补齐；
 *  - 去重：按消息 id（BigInt 比较——雪花超 Number 安全范围，禁 Number()）；
 *  - 发送：clientMsgId 一次逻辑发送生成一次，失败重试**复用原键**（后端 uk 幂等兜底网络超时重发）；
 *  - 已读：页面可见且确有新消息时才调 read（快照幂等），隐藏时轮询/标读均不执行，回前台先补拉再标读。
 *
 * 轮询用模块内 ref 单飞（同一时刻不并发两个增量请求）；卸载/切路由清理定时器与监听器。
 */

const POLL_MS = 9_000
const PAGE = 20
const CONTENT_MAX = 500

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

const ID_RE = /^\d{1,19}$/

export default function ChatPage() {
  const { conversationId = '' } = useParams()
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const { push } = useToast()
  const fail = useBizFail()
  const userId = tokenStore.getUserId()

  const [conv, setConv] = useState<ChatConversationVO | null>(null)
  const [messages, setMessages] = useState<ChatMessageVO[]>([]) // ASC 显示序
  const [hasMoreBefore, setHasMoreBefore] = useState(false)
  const [loadingBefore, setLoadingBefore] = useState(false)
  const [initState, setInitState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [initError, setInitError] = useState<{ code: number; message: string } | null>(null)
  const [retryTick, setRetryTick] = useState(0)
  const [draft, setDraft] = useState('')
  const [pending, setPending] = useState<PendingMsg[]>([])
  const [hasNewBelow, setHasNewBelow] = useState(false)
  /** 图片大图预览（只消费服务端 mediaUrl 或 pending 本地 blob，绝不拼 URL） */
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)

  const listRef = useRef<HTMLDivElement>(null)
  const endRef = useRef<HTMLDivElement>(null)
  const imageInputRef = useRef<HTMLInputElement>(null)
  const aliveRef = useRef(true)
  const atBottomRef = useRef(true)
  const latestIdRef = useRef<string | null>(null) // 当前最新消息 id（增量游标源）
  const readMarkedRef = useRef<string | null>(null) // 已触发 read 时的 max id（防无谓重复调用）
  const messagesRef = useRef<ChatMessageVO[]>([])
  const conversationIdRef = useRef(conversationId)
  conversationIdRef.current = conversationId
  const ctxSeqRef = useRef(0)
  const ctxRef = useRef<ChatContext | null>(null)
  const ctxAbortRef = useRef<AbortController | null>(null)
  /** 单飞按上下文隔离：旧上下文的 tick 不能清掉新上下文的在途标记。 */
  const pollingCtxRef = useRef<ChatContext | null>(null)
  /**
   * 加载更早的滚动策略（刻意简单，由布局本身保证）：「加载更早的消息」按钮**置顶**、
   * 新页插入在**按钮下方**——用户能点按钮时视口必然在顶部区域，插入不会把用户推离；
   * 滚动保持交给浏览器原生 scroll anchoring（锚住用户正在看的元素）。
   * ⚠ 曾手工按高度差补偿 scrollTop：实测反而把"点了顶部按钮"的用户往下推进新内容（探针实锤），
   * 已移除——不要在按钮置顶布局上再做补偿。
   */

  /** 路由 id 已变、初始化 effect 尚未执行的微任务窗口里，也必须让旧会话上下文立即失效。 */
  const isChatCurrent = useCallback(
    (ctx: ChatContext) =>
      ctx.conversationId === conversationIdRef.current &&
      isChatContextCurrent(ctxRef.current, ctx, aliveRef.current),
    [],
  )

  /* ---------------- 已读同步（可见 + 确有新消息才调接口） ---------------- */
  const syncRead = useCallback(async (ctx: ChatContext) => {
    const signal = ctxAbortRef.current?.signal
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
    void refreshUnreadCount()
  }, [isChatCurrent])

  /* ---------------- 增量合并（afterId 方向：ASC 到达） ---------------- */
  const applyIncoming = (ctx: ChatContext, incomingAsc: ChatMessageVO[]) => {
    if (!isChatCurrent(ctx)) return
    const commit = commitIncoming(
      ctx,
      ctxRef.current,
      aliveRef.current,
      messagesRef.current,
      latestIdRef.current,
      incomingAsc,
    )
    if (!commit.applied) return
    messagesRef.current = commit.messages
    setMessages(commit.messages)
    latestIdRef.current = commit.latestId
    if (commit.advanced && atBottomRef.current) {
      requestAnimationFrame(() => endRef.current?.scrollIntoView({ block: 'end', behavior: 'smooth' }))
    } else if (commit.advanced && incomingAsc.some((m) => m.senderType === 'STORE')) {
      setHasNewBelow(true) // 用户在看历史：不强制拉回底部，给轻量提示
    }
    void syncRead(ctx)
  }

  /* ---------------- 初始化：详情 + 最近一页 ---------------- */
  useEffect(() => {
    const seq = ++ctxSeqRef.current
    ctxAbortRef.current?.abort()
    const ac = new AbortController()
    ctxAbortRef.current = ac
    const ctx: ChatContext = { conversationId, storeId: null, seq }
    ctxRef.current = ctx
    aliveRef.current = true
    pollingCtxRef.current = null
    setInitState('loading')
    setInitError(null)
    setConv(null)
    messagesRef.current = []
    setMessages([])
    setLoadingBefore(false)
    setPending((prev) => {
      for (const p of prev) if (p.localUrl) URL.revokeObjectURL(p.localUrl) // 切会话丢弃本地预览
      return []
    })
    setHasNewBelow(false)
    setPreviewUrl(null)
    latestIdRef.current = null
    readMarkedRef.current = null

    const cleanup = () => {
      ac.abort()
      aliveRef.current = false
      if (ctxRef.current === ctx) ctxRef.current = null
      if (pollingCtxRef.current === ctx) pollingCtxRef.current = null
    }

    if (!ID_RE.test(conversationId)) {
      setInitState('error')
      setInitError({ code: 10001, message: '会话地址不正确' })
      return cleanup
    }
    if (!userId) {
      navigate('/login', { replace: true })
      return cleanup
    }

    void (async () => {
      try {
        const [c, page] = await Promise.all([
          chatApi.conversation(conversationId, ac.signal),
          chatApi.messages(conversationId, { limit: PAGE }, ac.signal),
        ])
        if (ac.signal.aborted || !isChatCurrent(ctx)) return
        const asc = [...(page.list ?? [])].reverse() // DESC 最近一页 → ASC 显示序
        setConv(c)
        messagesRef.current = asc
        setMessages(asc)
        setHasMoreBefore(page.hasMore)
        latestIdRef.current = asc.length ? String(asc[asc.length - 1].id) : null
        setInitState('ready')
        // 初始即滚到底（聊天惯例：定格在最新）
        requestAnimationFrame(() => endRef.current?.scrollIntoView({ block: 'end' }))
        // 进入会话：按实际读取情况标记已读（页面可见时；隐藏时不标）
        void syncRead(ctx)
      } catch (e) {
        if (ac.signal.aborted || !isChatCurrent(ctx)) return
        const biz = e instanceof BizError ? e : null
        setInitState('error')
        setInitError({
          code: biz?.code ?? -1,
          message: biz ? biz.message : '会话加载失败，请稍后重试',
        })
      }
    })()
    return cleanup
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [conversationId, userId, retryTick, navigate])

  /* ---------------- 轮询（可见时 9s 一跳；单飞；hasMore 补齐） ---------------- */
  useEffect(() => {
    if (initState !== 'ready') return
    const ctx = ctxRef.current
    if (!ctx) return
    const signal = ctxAbortRef.current?.signal
    const tick = async () => {
      if (document.visibilityState !== 'visible' || !isChatCurrent(ctx)) return
      const inflight = pollingCtxRef.current
      if (inflight && isChatCurrent(inflight)) return
      pollingCtxRef.current = ctx
      try {
        let cursor = latestIdRef.current
        if (!cursor) {
          // 空会话：无游标拉最近一页"发现"新消息（不得伪造游标）
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
        // 轮询失败保留现状，不清空会话；下个周期/回前台自动重试
      } finally {
        if (pollingCtxRef.current === ctx) pollingCtxRef.current = null
      }
    }
    const timer = window.setInterval(() => void tick(), POLL_MS)
    const onVis = () => {
      // 回前台：先补拉最新，再按实际读取标读（syncRead 在 applyIncoming 内随之触发）
      if (document.visibilityState === 'visible') void tick()
    }
    document.addEventListener('visibilitychange', onVis)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVis)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initState, conversationId])

  /* ---------------- 加载更早（beforeId，保持阅读位置） ---------------- */
  const loadBefore = async () => {
    const ctx = ctxRef.current
    if (!ctx || loadingBefore || messagesRef.current.length === 0 || !isChatCurrent(ctx)) return
    const signal = ctxAbortRef.current?.signal
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
        fail(result.error)
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
      setPending((list) => list.filter((x) => x.tmpId !== p.tmpId))
      applyIncoming(ctx, [real]) // 合并真实消息（轮询同消息按 id 去重）
    } catch (e) {
      if (!isChatCurrent(ctx)) return
      setPending((list) => list.map((x) => (x.tmpId === p.tmpId ? { ...x, status: 'failed' } : x)))
      push(e instanceof BizError ? e.message : '发送失败，请重试', 'error')
    }
  }

  const send = async () => {
    const text = draft.trim()
    if (!text) return
    const ctx = ctxRef.current
    if (!ctx || !isChatCurrent(ctx)) return
    if (text.length > CONTENT_MAX) {
      push(`消息不超过 ${CONTENT_MAX} 字`, 'info')
      return
    }
    const signal = ctxAbortRef.current?.signal
    const clientMsgId = newClientMsgId()
    const tmpId = `tmp-${clientMsgId}`
    setPending((list) => [...list, { tmpId, clientMsgId, content: text, status: 'sending' }])
    setDraft('')
    atBottomRef.current = true
    requestAnimationFrame(() => endRef.current?.scrollIntoView({ block: 'end' }))
    await deliver(ctx, signal, { tmpId, clientMsgId, content: text })
  }

  const retry = (p: PendingMsg) => {
    const ctx = ctxRef.current
    if (!ctx || !isChatCurrent(ctx)) return
    const signal = ctxAbortRef.current?.signal
    if (p.mediaKey) {
      // 图片：PUT 已成功、仅发送失败 → 复用原 clientMsgId + mediaKey 重发（不重传）
      setPending((list) => list.map((x) => (x.tmpId === p.tmpId ? { ...x, status: 'sending' } : x)))
      void deliverImageSend(ctx, signal, p.tmpId, p.clientMsgId, p.mediaKey)
      return
    }
    if (p.file) {
      // 图片：上传段失败 → 重传（新 key；消息幂等仍由 clientMsgId 兜底）
      setPending((list) => list.map((x) => (x.tmpId === p.tmpId ? { ...x, status: 'uploading' } : x)))
      void deliverImage(ctx, signal, p)
      return
    }
    setPending((list) => list.map((x) => (x.tmpId === p.tmpId ? { ...x, status: 'sending' } : x)))
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
      setPending((list) => {
        const gone = list.find((x) => x.tmpId === tmpId)
        if (gone?.localUrl) URL.revokeObjectURL(gone.localUrl) // 真实消息已合并，本地预览功成身退
        return list.filter((x) => x.tmpId !== tmpId)
      })
      applyIncoming(ctx, [real])
    } catch (e) {
      if (!isChatCurrent(ctx)) return
      setPending((list) => list.map((x) => (x.tmpId === tmpId ? { ...x, status: 'failed' } : x)))
      push(e instanceof BizError ? e.message : '发送失败，请重试', 'error')
    }
  }

  const deliverImage = async (ctx: ChatContext, signal: AbortSignal | undefined, p: PendingMsg) => {
    if (!p.file || !isChatCurrent(ctx)) return
    try {
      const key = await uploadImage(p.file, 'CHAT_IMAGE', ctx.conversationId, signal) // 会话归属由服务端校验（10003/10004）
      if (!isChatCurrent(ctx)) return
      setPending((list) => list.map((x) => (x.tmpId === p.tmpId ? { ...x, status: 'sending', mediaKey: key } : x)))
      await deliverImageSend(ctx, signal, p.tmpId, p.clientMsgId, key)
    } catch (e) {
      if (!isChatCurrent(ctx)) return
      setPending((list) => list.map((x) => (x.tmpId === p.tmpId ? { ...x, status: 'upload-failed' } : x)))
      push(e instanceof UploadError ? e.message : '图片上传失败，请重试', 'error')
    }
  }

  const pickImage = (file: File | undefined) => {
    if (!file) return
    const ctx = ctxRef.current
    if (!ctx || !isChatCurrent(ctx)) return
    const bad = validateImage(file) // 前端预检（MIME/≤5MB）；服务端另有权威校验
    if (bad) {
      push(bad, 'info')
      return
    }
    const signal = ctxAbortRef.current?.signal
    const clientMsgId = newClientMsgId() // 一次逻辑发送一个键；上传/发送两段重试都复用它
    const tmpId = `tmp-${clientMsgId}`
    const localUrl = URL.createObjectURL(file)
    const entry: PendingMsg = { tmpId, clientMsgId, content: '[图片]', status: 'uploading', localUrl, file }
    setPending((list) => [...list, entry])
    atBottomRef.current = true
    requestAnimationFrame(() => endRef.current?.scrollIntoView({ block: 'end' }))
    void deliverImage(ctx, signal, entry)
  }

  /* ---------------- 滚动策略 ---------------- */
  const onScroll = () => {
    const el = listRef.current
    if (!el) return
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80
    atBottomRef.current = nearBottom
    if (nearBottom) setHasNewBelow(false)
  }

  /* ---------------- 本次入口 / 首建来源（展示口径严格区分） ---------------- */
  const entryBizId = searchParams.get('bizId')
  const entryBizModule = searchParams.get('biz')
  const entryValid = !!entryBizId && ID_RE.test(entryBizId) && entryBizModule === 'appointment'
  const originValid = conv?.originBizModule === 'appointment' && !!conv?.originBizId && ID_RE.test(String(conv.originBizId))
  const relatedLink = entryValid
    ? { id: entryBizId as string, label: '关联订单' } // 本次入口（本次响应回显，编码在 URL）
    : originValid
      ? { id: String(conv?.originBizId), label: '首次关联订单' } // 只标注来源，不称"当前订单"
      : null

  /* ---------------- 渲染 ---------------- */
  if (initState === 'error') {
    const denied = initError?.code === 10004 || initError?.code === 10003
    return (
      <main className="min-h-[100dvh] bg-brand-gradient">
        <div className="mx-auto max-w-md px-4 pb-10">
          <PageHeader title="门店沟通" fallback="/messages" />
          <div className="mt-6 rounded-3xl bg-white/85 p-8 text-center shadow-soft ring-1 ring-navy/5">
            <p className="text-sm font-semibold text-ink">{denied ? '会话不存在或无权查看' : '会话没打开'}</p>
            <p className="mt-1 text-xs leading-relaxed text-muted">
              {denied ? '这个会话不属于当前登录账号，可能已更换账号或链接有误。' : (initError?.message ?? '请稍后重试')}
            </p>
            <div className="mt-4 flex justify-center gap-2.5">
              {!denied && (
                <button
                  type="button"
                  onClick={() => setRetryTick((n) => n + 1)}
                  className="inline-flex h-10 items-center rounded-2xl bg-btn-gradient px-5 text-sm font-semibold text-white shadow-btn"
                >
                  重试
                </button>
              )}
              <button
                type="button"
                onClick={() => navigate('/messages')}
                className="inline-flex h-10 items-center rounded-2xl bg-warmgray px-5 text-sm font-semibold text-navy/80"
              >
                回消息中心
              </button>
            </div>
          </div>
        </div>
      </main>
    )
  }

  return (
    <main className="flex h-[100dvh] flex-col bg-brand-gradient">
      <div className="mx-auto flex w-full max-w-md flex-1 flex-col overflow-hidden">
        <PageHeader
          title={conv?.storeName ?? '门店沟通'}
          fallback="/messages"
          right={
            relatedLink
              ? { label: relatedLink.label, onClick: () => navigate(`/orders/${relatedLink.id}`) }
              : undefined
          }
        />

        {/* 消息区（滚动保持交给浏览器原生 anchoring：插入点在按钮下方，顶部用户不被推离） */}
        <div ref={listRef} onScroll={onScroll} className="flex-1 overflow-y-auto px-4 pb-3">
          {initState === 'loading' && (
            <div className="mt-4 space-y-3" aria-busy="true">
              <SkeletonBubble side="left" />
              <SkeletonBubble side="right" />
              <SkeletonBubble side="left" />
            </div>
          )}

          {initState === 'ready' && hasMoreBefore && (
            <button
              type="button"
              onClick={() => void loadBefore()}
              disabled={loadingBefore}
              className="mx-auto mt-2 block rounded-full bg-white/85 px-4 py-1.5 text-[11px] font-semibold text-navy ring-1 ring-navy/5 disabled:opacity-60"
            >
              {loadingBefore ? '加载中…' : '加载更早的消息'}
            </button>
          )}

          {initState === 'ready' && messages.length === 0 && pending.length === 0 && (
            <div className="mt-10 rounded-3xl bg-white/85 p-6 text-center shadow-soft ring-1 ring-navy/5">
              <p className="text-sm font-semibold text-ink">开始和门店沟通吧</p>
              <p className="mt-1 text-xs leading-relaxed text-muted">
                可以咨询预约时间、宠物状态或服务内容；消息记录会一直保留在这里。
              </p>
            </div>
          )}

          {initState === 'ready' && (
            <div className="pt-2">
              {messages.map((m, i) => (
                <Bubble key={String(m.id)} msg={m} prev={messages[i - 1]} onPreview={setPreviewUrl} />
              ))}
              {pending.map((p) => (
                <PendingBubble key={p.tmpId} p={p} onRetry={() => retry(p)} />
              ))}
              <div ref={endRef} />
            </div>
          )}
        </div>

        {/* 新消息提示（用户不在底部时出现；点击回到底部） */}
        {hasNewBelow && (
          <button
            type="button"
            onClick={() => {
              setHasNewBelow(false)
              atBottomRef.current = true
              endRef.current?.scrollIntoView({ block: 'end', behavior: 'smooth' })
            }}
            className="mx-auto -mt-10 mb-1 block rounded-full bg-navy px-4 py-1.5 text-[11px] font-semibold text-white shadow-btn"
          >
            有新消息 ↓
          </button>
        )}

        {/* 输入条（吸底 + 安全区） */}
        <div className="border-t border-navy/5 bg-ivory/95 px-3 pb-[calc(env(safe-area-inset-bottom)+10px)] pt-2.5 backdrop-blur">
          <div className="flex items-end gap-2">
            <button
              type="button"
              onClick={() => imageInputRef.current?.click()}
              aria-label="发送图片"
              className="grid h-[42px] w-[42px] shrink-0 place-items-center rounded-2xl bg-white/90 text-navy/70 shadow-soft ring-1 ring-navy/5 transition active:scale-[0.98]"
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
              rows={1}
              maxLength={CONTENT_MAX + 20}
              placeholder="输入消息…"
              aria-label="消息输入框"
              className="max-h-32 min-h-[42px] flex-1 resize-none rounded-2xl bg-white/90 px-3.5 py-2.5 text-sm leading-relaxed text-ink shadow-soft ring-1 ring-navy/5 outline-none focus:ring-2 focus:ring-navy/25"
            />
            <button
              type="button"
              onClick={() => void send()}
              disabled={!draft.trim()}
              className={cx(
                'h-[42px] shrink-0 rounded-2xl px-4 text-sm font-semibold text-white transition',
                draft.trim() ? 'bg-btn-gradient shadow-btn active:scale-[0.98]' : 'bg-navy/25',
              )}
            >
              发送
            </button>
          </div>
          {draft.length > CONTENT_MAX && (
            <p className="mt-1 text-right text-[10px] text-rose-500">超出 {CONTENT_MAX} 字上限，请精简后再发送</p>
          )}
        </div>
      </div>

      {/* 大图预览：只显示消息的 mediaUrl（服务端签名）或 pending 本地 blob，前端绝不拼 URL */}
      {previewUrl && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-navy/70 p-4"
          role="dialog"
          aria-label="图片预览"
          onClick={() => setPreviewUrl(null)}
        >
          <img src={previewUrl} alt="图片预览" className="max-h-[88vh] max-w-full rounded-2xl object-contain shadow-card" />
          <button
            type="button"
            aria-label="关闭预览"
            onClick={(e) => {
              e.stopPropagation()
              setPreviewUrl(null)
            }}
            className="absolute right-4 top-4 grid h-9 w-9 place-items-center rounded-full bg-white/90 text-navy shadow-btn"
          >
            <IconX className="h-5 w-5" />
          </button>
        </div>
      )}
    </main>
  )
}

/* ---------------- 气泡 ---------------- */

const dayOf = (iso: string) => iso.slice(0, 10)
const hmOf = (iso: string) => (iso.length >= 16 ? iso.slice(11, 16) : '')

function dayLabel(iso: string): string {
  return dayOf(iso) === todayISO() ? '今天' : dateText(dayOf(iso))
}

function Bubble({ msg, prev, onPreview }: { msg: ChatMessageVO; prev?: ChatMessageVO; onPreview: (url: string) => void }) {
  const mine = msg.senderType === 'CUSTOMER'
  const showDay = !prev || dayOf(prev.createdAt) !== dayOf(msg.createdAt)
  return (
    <>
      {showDay && <p className="my-3 text-center text-[10px] text-muted/80">{dayLabel(msg.createdAt)}</p>}
      <div className={cx('mb-2 flex', mine ? 'justify-end' : 'justify-start')}>
        <div className="max-w-[78%]">
          {!mine && <p className="mb-0.5 px-1 text-[10px] text-muted">{msg.senderName || '门店'}</p>}
          {msg.messageType === 'IMAGE' ? (
            <ImageContent url={msg.mediaUrl} onPreview={onPreview} />
          ) : (
            <div
              className={cx(
                'whitespace-pre-wrap break-words rounded-2xl px-3.5 py-2.5 text-sm leading-relaxed',
                mine
                  ? 'rounded-br-md bg-btn-gradient text-white shadow-btn'
                  : 'rounded-bl-md bg-white/95 text-ink shadow-soft ring-1 ring-navy/5',
              )}
            >
              {msg.content}
            </div>
          )}
          <p className={cx('mt-0.5 px-1 text-[10px] text-muted/70', mine ? 'text-right' : 'text-left')}>
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
      <div className="rounded-2xl bg-white/95 px-3.5 py-2.5 text-xs leading-relaxed text-muted shadow-soft ring-1 ring-navy/5">
        图片加载失败，稍后重新进入会话可自动刷新
      </div>
    )
  }
  return (
    <button
      type="button"
      onClick={() => onPreview(url)}
      aria-label="查看大图"
      className="block cursor-zoom-in overflow-hidden rounded-2xl shadow-soft ring-1 ring-navy/5"
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
    <div className="mb-2 flex justify-end">
      <div className="max-w-[78%]">
        {isImage ? (
          <div className={cx('overflow-hidden rounded-2xl ring-1', failed ? 'ring-rose-200' : 'ring-navy/5')}>
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
              failed ? 'bg-rose-50 text-rose-700 ring-1 ring-rose-200/80' : 'bg-btn-gradient text-white opacity-70 shadow-btn',
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

function SkeletonBubble({ side }: { side: 'left' | 'right' }) {
  return (
    <div className={cx('flex', side === 'right' ? 'justify-end' : 'justify-start')} aria-hidden="true">
      <div className={cx('h-9 animate-pulse rounded-2xl bg-white/70', side === 'right' ? 'w-2/5' : 'w-3/5')} />
    </div>
  )
}
