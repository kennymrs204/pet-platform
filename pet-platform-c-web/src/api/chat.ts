import { http } from './client'
import type { PageResult } from './catalog'

/**
 * C 端门店沟通（P3）：与 B 端共用同一套会话/消息（V11），只走本项目统一 http 封装
 * （base 已含 /api/v1，勿重复拼）。所有 id 均为 Long 字符串化——**一律按 string 处理**，
 * 排序/游标比较禁止 Number() 转换（雪花 id 超出安全整数范围）。
 *
 * 游标语义（与后端 P2.1 契约逐字一致，前端不得改写）：
 *   无游标：最近一页，id DESC；beforeId：更早，id DESC；afterId：更新，**id ASC**；
 *   两者互斥（后端 10001）；limit 默认 20 / 上限 50；hasMore 对应当前方向是否还有。
 */

export interface ChatConversationVO {
  id: string
  storeId: string
  storeName: string
  lastMessagePreview: string | null
  lastMessageAt: string | null
  /** long → 字符串化；展示前用 Number() 转换（仅计数用途） */
  unread: string | number
  /** 首建来源（弱引用）——≠本次入口；是否展示由调用方按上下文标注 */
  originBizModule: string | null
  originBizId: string | null
}

/** open 响应：conversation=权威态；entry*=本次入口回显（不落库、不覆盖 origin）。 */
export interface OpenConversationResult {
  conversation: ChatConversationVO
  entryBizModule: string | null
  entryBizId: string | null
}

export interface ChatMessageVO {
  id: string
  /** CUSTOMER/STORE——前端据此区分左右气泡 */
  senderType: string
  /** 服务端快照：B 端=员工姓名，C 端=顾客昵称 */
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

export interface ChatMessagePageVO {
  list: ChatMessageVO[]
  hasMore: boolean
}

export interface UnreadSummaryVO {
  notificationCount: string | number
  chatCount: string | number
  total: string | number
}

export const chatApi = {
  /** 我的会话列表（last_message_at desc）。 */
  conversations: (pageNum = 1, pageSize = 20, signal?: AbortSignal) =>
    http.get<PageResult<ChatConversationVO>>(
      `/customer/conversations?pageNum=${pageNum}&pageSize=${pageSize}`,
      { auth: true, signal },
    ),

  /** get-or-create：同店同客复用同一会话；biz 只影响首建 origin 与本次 entry 回显。 */
  open: (body: { storeId: string; bizModule?: string; bizId?: string }) =>
    http.post<OpenConversationResult>('/customer/conversations', body, { auth: true }),

  /** 单会话详情（刷新恢复用；只读不清未读）。 */
  conversation: (id: string, signal?: AbortSignal) =>
    http.get<ChatConversationVO>(`/customer/conversations/${id}`, { auth: true, signal }),

  /** 双方向共一口：无游标=最近一页；beforeId=更早；afterId=更新（两游标由后端裁决互斥）。 */
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
    return http.get<ChatMessagePageVO>(`/customer/conversations/${id}/messages${qs ? `?${qs}` : ''}`, {
      auth: true,
      signal,
    })
  },

  /** 发送消息（幂等：同 clientMsgId 重试返回原消息，不落第二条）。IMAGE 时 content 固定 [图片] 且 mediaKey 必填。 */
  send: (
    id: string,
    body: { content: string; clientMsgId: string; messageType?: string; mediaKey?: string; bizModule?: string; bizId?: string },
    signal?: AbortSignal,
  ) => http.post<ChatMessageVO>(`/customer/conversations/${id}/messages`, body, { auth: true, signal }),

  /** 标记客户侧已读（幂等快照式；只影响聊天未读，不动系统通知）。 */
  markRead: (id: string, signal?: AbortSignal) =>
    http.post<void>(`/customer/conversations/${id}/read`, undefined, { auth: true, signal }),

  /** 通知×聊天合计未读（铃铛与分账的唯一数字源；total 已是两源真实合计）。 */
  unreadSummary: () => http.get<UnreadSummaryVO>('/customer/unread-summary', { auth: true }),
}

/** 客户端幂等键：一次逻辑发送生成一次；同一条消息的重试必须复用（≤64）。 */
export function newClientMsgId(): string {
  const rnd = globalThis.crypto?.randomUUID?.() ?? Math.random().toString(36).slice(2)
  return `c${Date.now().toString(36)}${rnd}`.slice(0, 64)
}
