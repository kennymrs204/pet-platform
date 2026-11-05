import { useRef, useState } from 'react'
import { AppointmentSelfView, CANCEL_ERROR_TEXT, appointmentApi } from '../../api/appointment'
import { BizCode, BizError } from '../../api/types'
import { fenToYuan } from '../../api/catalog'
import { useToast } from '../ui/Toast'
import { IconX } from '../ui/icons'
import { errText } from '../../auth/useBizFail'
import { apptWhenText } from '../../lib/orderBoard'
import { cx } from '../../lib/cx'

/** 一次取消动作一个 requestId（Redis SETNX TTL 5min）；重试复用，命中 10005 才换新。 */
function newRequestId(): string {
  const rnd = globalThis.crypto?.randomUUID?.() ?? Math.random().toString(36).slice(2)
  return `cx${Date.now().toString(36)}${rnd}`.slice(0, 64)
}

/**
 * 取消预约面板（ADR-023 + ADR-034）。未支付=关单并释放资源；已支付=订单转 REFUNDING + 生成 APPLYING 退款申请单，
 * 之后由店长/平台管理员审批。文案只说"进入审核"，不承诺到账时间——审批是异步的人工动作。
 * 退款金额由后端按当前上架的 cancel_free_hours / cancel_penalty_rate 实时算，前端不预估。
 */
export default function CancelDialog({
  appt,
  storeName,
  onClose,
  onCancelled,
}: {
  appt: AppointmentSelfView
  storeName: string
  onClose: () => void
  onCancelled: (refundState: string, refundAmount: string | number | null) => void
}) {
  const { push } = useToast()
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  const reqId = useRef(newRequestId())
  const paid = appt.status === 'CONFIRMED'

  const submit = async () => {
    if (reason.length > 200) {
      setErr('取消原因不超过 200 字')
      return
    }
    setBusy(true)
    setErr('')
    try {
      const r = await appointmentApi.cancel(appt.id, { requestId: reqId.current, reason: reason.trim() || null })
      push(paid ? '已取消，退款申请已提交' : '已取消，资源已释放', 'success')
      onCancelled(r.refundState, r.refundAmount)
      onClose()
    } catch (e) {
      const text = e instanceof BizError ? CANCEL_ERROR_TEXT[e.code] ?? errText(e) : errText(e)
      setErr(text)
      if (e instanceof BizError && e.code === BizCode.RATE_LIMITED) reqId.current = newRequestId()
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-navy/40 backdrop-blur-sm" onClick={onClose}>
      <div
        className="animate-fade-up w-full max-w-md rounded-t-3xl bg-white px-5 pb-8 pt-4 shadow-card"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between">
          <h2 className="text-base font-bold text-ink">取消预约</h2>
          <button type="button" onClick={onClose} aria-label="关闭" className="text-muted">
            <IconX className="h-5 w-5" />
          </button>
        </div>

        <div className="mt-3 rounded-2xl bg-warmgray/70 px-3.5 py-3 text-xs">
          <p className="font-bold text-ink">{appt.serviceNameSnapshot}</p>
          <p className="mt-1 text-muted">
            {apptWhenText(appt)} · {storeName}
          </p>
          <p className="mt-1 font-mono font-bold text-navy">¥{fenToYuan(appt.priceSnapshot)}</p>
        </div>

        <p className="mt-3 text-[11px] leading-relaxed text-muted">
          {paid
            ? '这条预约已支付，所以取消要经门店审核：提交后预约进入「取消申请审核中」，服务时段仍为你保留，门店店长或平台管理员按取消政策核定。审核通过并退款成功后，预约才会真正取消、金额退回原支付渠道；若被拒绝，预约恢复可服务状态、订单仍然有效。进度本页持续可见。'
            : '这条预约尚未支付。取消只会关闭订单并释放已锁定的资源，不产生费用。'}
          退款金额按预约创建时保存的取消政策计算；早期未保存政策快照的预约，按门店当前有效政策计算。提交后在详情页显示结果。
        </p>

        <label className="mt-3 block">
          <span className="mb-1 block px-1 text-[11px] font-semibold text-navy/70">取消原因（选填，≤200 字）</span>
          <textarea
            value={reason}
            onChange={(e) => {
              setReason(e.target.value)
              setErr('')
            }}
            rows={3}
            maxLength={220}
            placeholder="如：临时出差，改天再约"
            className="w-full resize-none rounded-2xl bg-warmgray/70 px-3.5 py-2.5 text-sm leading-relaxed text-ink ring-1 ring-navy/5 outline-none focus:ring-2 focus:ring-navy/25"
          />
          <span className="mt-0.5 block text-right text-[10px] text-muted">{reason.length}/200</span>
        </label>

        {err && <p className="mt-1 rounded-2xl bg-rose-50 px-3 py-2.5 text-[11px] leading-relaxed text-rose-600">{err}</p>}

        <div className="mt-4 flex gap-2">
          <button
            type="button"
            onClick={onClose}
            className="h-11 w-28 rounded-2xl bg-warmgray text-xs font-semibold text-navy/80"
          >
            再想想
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={() => void submit()}
            className={cx(
              'h-11 flex-1 rounded-2xl text-sm font-semibold text-white',
              busy ? 'bg-rose-300' : 'bg-rose-500 active:scale-[0.99]',
            )}
          >
            {busy ? '提交中…' : paid ? '确认取消并申请退款' : '确认取消'}
          </button>
        </div>
      </div>
    </div>
  )
}
