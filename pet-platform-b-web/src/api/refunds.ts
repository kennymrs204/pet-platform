import { http } from './client'
import type { PageData } from './dashboard'

/**
 * B 端退款审批接口（ADR-034）。两条权限码分工：
 * - 列表 `order:refund:view`：全部员工可看退款进度（V4 新增，任务角色也有）；
 * - 审批 `order:refund:approve`：仅店长与平台管理员（V2 既有）。
 *
 * 契约要点：
 * - 金额一律分（Long→字符串），展示走 fenToYuanText；
 * - **出参没有客户手机号/userId**：任务角色不该看客户联系方式（ADR-017 同一口径），
 *   所以这里只能看到"哪一单、多少钱、什么理由"，要看人得另持 customer:view:store；
 * - 审批通过=模拟退款成功（无支付渠道 SPI，也不写 pay_refund_txn），订单 REFUNDING→REFUNDED；
 * - 拒绝=订单回 PAID、预约恢复 CONFIRMED 可继续服务（ADR-043），须携带 1~200 字拒绝理由（V17），
 *   顾客在 C 端退款进度卡可见；
 * - 店长超额审批得 60006（sys_config 507 上限），跨店审批得 10004。
 */

export interface RefundItem {
  id: string
  refundNo: string
  orderId: string
  appointmentId: string | null
  storeId: string
  /** 退款金额（分） */
  amount: string
  /** 客户填写的申请理由 */
  reason: string
  /** 门店拒绝理由；仅 REJECTED 有值（V17），存量行为 null */
  rejectReason: string | null
  /** APPLYING/APPROVED/REJECTED/EXECUTING/SUCCESS/FAILED */
  status: string
  /** USER/STORE/ADMIN/SYSTEM */
  applyBy: string
  /** 审批人角色快照；未审为 null */
  approveRole: string | null
  createdAt: string
  refundedAt: string | null
}

export const REFUND_STATUS_LABEL: Record<string, string> = {
  APPLYING: '待处理',
  APPROVED: '审核通过',
  EXECUTING: '退款中',
  SUCCESS: '已退款',
  REJECTED: '已拒绝',
  FAILED: '退款失败',
}

export const refundsApi = {
  /** status 传 'APPLYING' 即"待处理退款"；传空=全部（含历史）。 */
  page: (status: string, pageNum = 1, pageSize = 20) => {
    const q = new URLSearchParams({ pageNum: String(pageNum), pageSize: String(pageSize) })
    if (status) q.set('status', status)
    return http.get<PageData<RefundItem>>(`/store/refunds?${q.toString()}`, { auth: true })
  },
  approve: (id: string) => http.post<RefundItem>(`/store/refunds/${id}/approve`, undefined, { auth: true }),
  reject: (id: string, rejectReason: string) =>
    http.post<RefundItem>(`/store/refunds/${id}/reject`, { rejectReason }, { auth: true }),
}

/** 当前工作门店是否具备退款审批身份（仅用于按钮显隐，真正的闸在服务端 @PreAuthorize）。 */
export function canApproveRefund(roles: string[]): boolean {
  return roles.includes('ADMIN') || roles.includes('STORE_MANAGER') || roles.includes('RECEPTIONIST')
}
