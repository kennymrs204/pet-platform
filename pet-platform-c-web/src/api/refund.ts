import { http } from './client'

/**
 * C 端退款进度（ADR-034 §3）。
 *
 * 取消响应里的 `refundState` 只代表**取消那一刻**；审批发生在之后，所以必须有这个可反复查的入口，
 * 否则用户刷新后就只剩"已取消"，钱退没退完全不知道（ADR-023 记在 B7 的欠账）。
 *
 * 口径：后端按 appointmentId → order → 最新一笔未拒退款单返回；无退款单（未支付取消/尚未申请）→ data=null。
 */

export interface RefundProgress {
  refundId: string
  refundNo: string
  orderId: string
  appointmentId: string | null
  /** APPLYING/SUCCESS/REJECTED/... */
  status: string
  /** 分 */
  amount: string
  /** 自己填的申请理由，回显便于核对 */
  reason: string
  /** 门店拒绝理由；仅 REJECTED 有值（V17），历史单为 null */
  rejectReason: string | null
  createdAt: string
  refundedAt: string | null
}

/** 用户看得懂的状态词，不暴露内部枚举值 */
export const REFUND_STATE_TEXT: Record<string, string> = {
  APPLYING: '退款审核中',
  APPROVED: '退款审核通过',
  EXECUTING: '退款处理中',
  SUCCESS: '退款成功',
  REJECTED: '退款未通过',
  FAILED: '退款处理失败',
}

export const refundApi = {
  progress: (appointmentId: string, signal?: AbortSignal) =>
    http.get<RefundProgress | null>(`/customer/refunds?appointmentId=${appointmentId}`, { auth: true, signal }),
}
