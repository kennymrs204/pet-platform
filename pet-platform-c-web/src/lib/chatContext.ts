/**
 * 聊天请求上下文守卫：每次会话切换生成新的 seq，所有 await 返回后都必须用
 * isChatContextCurrent 复核。aliveRef 只证明组件生命周期，不能证明响应属于当前会话。
 */

export interface ChatContext {
  conversationId: string
  storeId: string | null
  seq: number
}

export interface ChatMessageLike {
  id: string
}

export interface IncomingCommit<T extends ChatMessageLike> {
  applied: boolean
  messages: T[]
  latestId: string | null
  advanced: boolean
}

export interface BeforeCommit<T extends ChatMessageLike> {
  applied: boolean
  messages: T[]
}

export interface ReadTarget {
  conversationId: string
  latestId: string
}

export interface ChatMessagePage<T extends ChatMessageLike> {
  list?: T[] | null
  hasMore: boolean
}

export interface HistoryCommit<T extends ChatMessageLike> {
  messages: T[]
  hasMore: boolean
}

export type GuardedAsyncResult<T> =
  | { status: 'ok'; value: T }
  | { status: 'stale' }
  | { status: 'noop' }
  | { status: 'error'; error: unknown }

const MESSAGE_ID_RE = /^\d{1,19}$/

/** 雪花 id 比较：BigInt 全精度（字符串比较在等长雪花下等价，但夹具/历史上存在短 id，统一走 BigInt）。 */
export function cmpId(a: string, b: string): number {
  const x = BigInt(a)
  const y = BigInt(b)
  return x === y ? 0 : x < y ? -1 : 1
}

/** 按 id 去重合并为 ASC（id 升序）；无新增时原引用返回（避免无谓重渲染）。 */
export function mergeMessages<T extends ChatMessageLike>(prev: T[], incoming: T[]): T[] {
  const byId = new Map<string, T>()
  for (const m of prev) byId.set(String(m.id), m)
  let changed = false
  for (const m of incoming) {
    const k = String(m.id)
    if (!byId.has(k)) {
      byId.set(k, m)
      changed = true
    }
  }
  if (!changed) return prev
  return Array.from(byId.values()).sort((a, b) => cmpId(String(a.id), String(b.id)))
}

export function isChatContextCurrent(
  current: ChatContext | null,
  ctx: ChatContext,
  alive = true,
): boolean {
  return (
    alive &&
    current !== null &&
    current.seq === ctx.seq &&
    current.conversationId === ctx.conversationId &&
    current.storeId === ctx.storeId
  )
}

/** 增量消息（afterId 方向）只能在发起请求的上下文仍是当前上下文时合并；游标严格单调不回退。 */
export function commitIncoming<T extends ChatMessageLike>(
  ctx: ChatContext,
  current: ChatContext | null,
  alive: boolean,
  prevMessages: T[],
  prevLatestId: string | null,
  incoming: T[],
): IncomingCommit<T> {
  if (!incoming.length || !isChatContextCurrent(current, ctx, alive)) {
    return { applied: false, messages: prevMessages, latestId: prevLatestId, advanced: false }
  }
  const lastIncomingId = String(incoming[incoming.length - 1].id)
  const advanced = !prevLatestId || cmpId(lastIncomingId, prevLatestId) > 0
  return {
    applied: true,
    messages: mergeMessages(prevMessages, incoming),
    latestId: advanced ? lastIncomingId : prevLatestId,
    advanced,
  }
}

/** 历史消息只向列表头部合并，不改变最新游标。 */
export function commitBefore<T extends ChatMessageLike>(
  ctx: ChatContext,
  current: ChatContext | null,
  alive: boolean,
  prevMessages: T[],
  incoming: T[],
): BeforeCommit<T> {
  if (!isChatContextCurrent(current, ctx, alive)) {
    return { applied: false, messages: prevMessages }
  }
  return { applied: true, messages: mergeMessages(prevMessages, incoming) }
}

/**
 * 已读必须同时绑定会话和游标快照：
 * 旧上下文的 latest 绝不能配新会话 id 发出，也不能在切换后继续改本地已读状态。
 */
export function resolveReadTarget(
  ctx: ChatContext,
  current: ChatContext | null,
  alive: boolean,
  latestId: string | null,
  readMarkedId: string | null,
  visible: boolean,
): ReadTarget | null {
  if (!visible || !isChatContextCurrent(current, ctx, alive)) return null
  if (!latestId || !MESSAGE_ID_RE.test(latestId)) return null
  if (readMarkedId && cmpId(latestId, readMarkedId) <= 0) return null
  return { conversationId: ctx.conversationId, latestId }
}

/**
 * 历史加载的异步提交闸：发请求前、响应返回后各校验一次页面级上下文；
 * 合并时读取最新消息快照，避免加载期间到达的新消息被旧响应覆盖。
 */
export async function loadHistoryForContext<T extends ChatMessageLike>(
  ctx: ChatContext,
  isCurrent: () => boolean,
  request: (ctx: ChatContext) => Promise<ChatMessagePage<T>>,
  currentMessages: () => T[],
): Promise<GuardedAsyncResult<HistoryCommit<T>>> {
  if (!isCurrent()) return { status: 'stale' }
  try {
    const page = await request(ctx)
    if (!isCurrent()) return { status: 'stale' }
    const asc = [...(page.list ?? [])].reverse()
    const commit = commitBefore(ctx, ctx, true, currentMessages(), asc)
    return {
      status: 'ok',
      value: { messages: commit.messages, hasMore: page.hasMore },
    }
  } catch (error) {
    return isCurrent() ? { status: 'error', error } : { status: 'stale' }
  }
}

/**
 * 已读的异步提交闸：目标在发请求前一次性绑定会话+游标；响应返回后仍须是同一页面级上下文，
 * 否则调用方不得更新已读游标、列表未读或铃铛。
 */
export async function markReadForContext(
  ctx: ChatContext,
  isCurrent: () => boolean,
  latestId: string | null,
  readMarkedId: string | null,
  visible: boolean,
  markRead: (target: ReadTarget, ctx: ChatContext) => Promise<unknown>,
): Promise<GuardedAsyncResult<ReadTarget>> {
  if (!isCurrent()) return { status: 'stale' }
  const target = resolveReadTarget(ctx, ctx, true, latestId, readMarkedId, visible)
  if (!target) return { status: 'noop' }
  try {
    await markRead(target, ctx)
    if (!isCurrent()) return { status: 'stale' }
    return { status: 'ok', value: target }
  } catch (error) {
    return isCurrent() ? { status: 'error', error } : { status: 'stale' }
  }
}
