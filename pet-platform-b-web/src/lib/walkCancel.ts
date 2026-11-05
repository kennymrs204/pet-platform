import type { RefundItem } from '../api/refunds'
import type { WalkingTask } from '../api/walking'

/**
 * 待审退款按预约建索引（只认 APPLYING）。
 *
 * 同一预约理应只有一笔待审退款：ADR-043 里 `CONFIRMED→CANCEL_REQUESTED` 与
 * `Refund(APPLYING)` 在同一事务内产生，审批是 status 条件更新（重复批得 60005）。
 * 但历史数据/重试仍可能留下多笔，这里固定取**最早申请**的一笔（同刻按 id 升序），
 * 保证同一行在页面上永远只有一个结论，且重复渲染结果稳定。
 */
export function pendingRefundByAppointment(refunds: readonly RefundItem[]): Map<string, RefundItem> {
  const out = new Map<string, RefundItem>()
  for (const r of refunds) {
    if (!r.appointmentId || r.status !== 'APPLYING') continue
    const prev = out.get(r.appointmentId)
    if (!prev || r.createdAt < prev.createdAt || (r.createdAt === prev.createdAt && r.id < prev.id)) {
      out.set(r.appointmentId, r)
    }
  }
  return out
}

/**
 * 遛狗页是否展示「同意/拒绝」。三道闸缺一不可：
 * ① 行状态=取消审核中；② 找到仍可审（APPLYING）的退款单；③ 调用者持审批身份（order:refund:approve）。
 *
 * 这**只是显隐判据**：真正的闸在服务端（@PreAuthorize + 退款单 status CAS + 店长单笔限额 60006），
 * 前端放行不代表后端会同意。
 */
export function canReviewCancel(
  task: Pick<WalkingTask, 'status'>,
  refund: RefundItem | null | undefined,
  canApprove: boolean,
): boolean {
  return canApprove && task.status === 'CANCEL_REQUESTED' && !!refund && refund.status === 'APPLYING'
}
