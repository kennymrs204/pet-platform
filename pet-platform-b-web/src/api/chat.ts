import { http } from './client'
import type { PageData } from './dashboard'

/**
 * B 端客户沟通（P4）：与 C 端共用同一套会话/消息（V11/P2）。
 *
 * - 门店范围只认已校验的 X-Store-Id（client 统一注入），前端**不传 storeId**；
 * - 所有 id 为 Long 字符串化——一律按 string 处理，排序/游标比较走 BigInt（雪花超 Number 安全范围）；
 * - 游标语义与后端 P2.1 契约一致：无游标=最近一页（id DESC）；beforeId=更早（DESC）；
 *   afterId=更新（**ASC**）；两游标互斥；limit 默认 20 / 上限 50。
 * - ⚠ 列表接口支持 keyword/unreadOnly（H1 服务端全量搜索）：过滤在服务端全量会话上完成、
 *   **过滤之后**再分页；门店范围仍只认 X-Store-Id（keyword 只是过滤条件，不能跨店）。
 */

export interface StoreConversationVO {
  id: string
  customerUserId: string
  customerName: string
  /** 前 3 后 4 脱敏（服务端出口唯一形态） */
  phoneMasked: string
  lastMessagePreview: string | null
  lastMessageAt: string | null
  /** long → 字符串化；展示前 Number()（仅计数用途） */
  unread: string | number
  /** 首建来源（弱引用）——≠当前讨论的订单 */
  originBizModule: string | null
  originBizId: string | null
}

export interface StoreOpenConversationResult {
  conversation: StoreConversationVO
  entryBizModule: string | null
  entryBizId: string | null
}

export interface StoreChatMessageVO {
  id: string
  /** CUSTOMER/STORE */
  senderType: string
  /** 服务端快照：门店侧=员工姓名，客户侧=顾客昵称 */
  senderName: string
  content: string
  /** TEXT/IMAGE（P7.1；历史行恒 TEXT）——气泡渲染分支依据 */
  messageType: string
  /**
   * IMAGE=后端签发的 presigned GET（600s，每次查询重签）；TEXT=null。
   * **前端只消费本字段，绝不根据 key 自拼 URL**（后端签名出口在 ChatGuard 之后）。
   */
  mediaUrl: string | null
  bizModule: string | null
  bizId: string | null
  createdAt: string
}

export interface StoreChatMessagePageVO {
  list: StoreChatMessageVO[]
  hasMore: boolean
}

export interface StoreUnreadSummaryVO {
  notificationCount: string | number
  chatCount: string | number
  total: string | number
  /** 消息中心列表完整未读（含 CHAT_MESSAGE），与 markAllReadInStore 更新范围一致；铃铛分账不用它 */
  listUnreadCount?: string | number
}

export const chatApi = {
  /**
   * 本店会话列表（服务端 last_message_at desc 排序；H1：keyword/unreadOnly 服务端全量过滤后分页）。
   * keyword 匹配姓名/完整手机号/脱敏手机号；unreadOnly=true 仅回未读会话。
   */
  conversations: (
    pageNum = 1,
    pageSize = 20,
    opts: { keyword?: string; unreadOnly?: boolean; signal?: AbortSignal } = {},
  ) => {
    const q = new URLSearchParams({ pageNum: String(pageNum), pageSize: String(pageSize) })
    const kw = (opts.keyword ?? '').trim()
    if (kw) q.set('keyword', kw)
    if (opts.unreadOnly) q.set('unreadOnly', 'true')
    return http.get<PageData<StoreConversationVO>>(`/store/conversations?${q.toString()}`, {
      auth: true,
      signal: opts.signal,
    })
  },

  /** get-or-create：必须传客户真实 customerUserId（360 的 userId）；同店同客复用既有会话。 */
  open: (body: { customerUserId: string; bizModule?: string; bizId?: string }) =>
    http.post<StoreOpenConversationResult>('/store/conversations', body, { auth: true }),

  /** 单会话详情（工作台恢复用；只读不清未读）。 */
  conversation: (id: string, signal?: AbortSignal) =>
    http.get<StoreConversationVO>(`/store/conversations/${id}`, { auth: true, signal }),

  /** 双方向共一口（与 C 端同语义）。 */
  messages: (
    id: string,
    opts: { beforeId?: string; afterId?: string; limit?: number } = {},
    signal?: AbortSignal,
  ) => {
    const q = new URLSearchParams()
    if (opts.beforeId) q.set('beforeId', opts.beforeId)
    if (opts.afterId) q.set('afterId', opts.afterId)
    if (opts.limit) q.set('limit', String(opts.limit))
    const qs = q.toString()
    return http.get<StoreChatMessagePageVO>(`/store/conversations/${id}/messages${qs ? `?${qs}` : ''}`, {
      auth: true,
      signal,
    })
  },

  /** 发送消息（幂等：同 clientMsgId 重试返回原消息）。发送人姓名由服务端按员工资料解析；IMAGE 时 content 固定 [图片] 且 mediaKey 必填。 */
  send: (
    id: string,
    body: { content: string; clientMsgId: string; messageType?: string; mediaKey?: string; bizModule?: string; bizId?: string },
    signal?: AbortSignal,
  ) => http.post<StoreChatMessageVO>(`/store/conversations/${id}/messages`, body, { auth: true, signal }),

  /** 门店级共享已读（幂等快照式；任何有权限员工读=门店已读）。 */
  markRead: (id: string, signal?: AbortSignal) =>
    http.post<void>(`/store/conversations/${id}/read`, undefined, { auth: true, signal }),

  /** 本店通知×聊天合计未读（铃铛唯一数字源；total 已是两源真实合计）。 */
  unreadSummary: () => http.get<StoreUnreadSummaryVO>('/store/unread-summary', { auth: true }),
}

/** 客户端幂等键（B 端前缀 b）：一次逻辑发送生成一次；同一条消息重试必须复用（≤64）。 */
export function newClientMsgId(): string {
  const rnd = globalThis.crypto?.randomUUID?.() ?? Math.random().toString(36).slice(2)
  return `b${Date.now().toString(36)}${rnd}`.slice(0, 64)
}
