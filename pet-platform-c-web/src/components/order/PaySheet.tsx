import { useState } from 'react'
import { BizCode, BizError } from '../../api/types'
import { OrderView, orderApi } from '../../api/order'
import { mockNotifyPaid } from '../../api/payment'
import { fenToYuan } from '../../api/catalog'
import { useToast } from '../ui/Toast'
import { IconX } from '../ui/icons'
import { errText } from '../../auth/useBizFail'
import { cx } from '../../lib/cx'

/**
 * 本地模拟支付面板。两步可见：① POST /orders/{id}/pay 拉起预支付拿 paymentNo；
 * ② POST /payment/mock/{paymentNo}/notify 模拟渠道回调，走真实 handleCallback 事件链
 * （订单 PAID → 预约 CONFIRMED → 托管单预生成），最后回读订单确认状态。
 * 明确标注"不产生真实扣款"，避免误认为已接微信支付。
 */
export default function PaySheet({
  order,
  onClose,
  onPaid,
}: {
  order: OrderView
  onClose: () => void
  onPaid: (paid: OrderView) => void
}) {
  const { push } = useToast()
  const [busy, setBusy] = useState(false)
  const [paymentNo, setPaymentNo] = useState<string | null>(null)
  const [err, setErr] = useState('')

  const prepay = async () => {
    setBusy(true)
    setErr('')
    try {
      const p = await orderApi.pay(order.id)
      setPaymentNo(p.paymentNo)
      push('已拉起预支付，可模拟渠道回调', 'info')
    } catch (e) {
      setErr(
        e instanceof BizError && e.code === BizCode.ORDER_NOT_PAYABLE
          ? '该订单当前状态不可支付（可能已支付或已超时取消）'
          : errText(e),
      )
    } finally {
      setBusy(false)
    }
  }

  const notify = async () => {
    if (!paymentNo) return
    setBusy(true)
    setErr('')
    try {
      await mockNotifyPaid(paymentNo)
      const paid = await orderApi.detail(order.id)
      onPaid(paid)
      push('支付成功，预约已确认', 'success')
      onClose()
    } catch (e) {
      setErr(errText(e))
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
          <h2 className="text-base font-bold text-ink">支付订单</h2>
          <button type="button" onClick={onClose} aria-label="关闭" className="text-muted">
            <IconX className="h-5 w-5" />
          </button>
        </div>

        <p className="mt-4 text-center font-mono text-3xl font-bold text-navy">¥{fenToYuan(order.amount)}</p>
        <p className="mt-1 text-center text-[11px] text-muted">
          订单号 <span className="font-mono">{order.orderNo}</span>
        </p>

        <div className="mt-5 space-y-2 border-t border-navy/5 pt-4 text-xs">
          <Row k="服务" v={order.itemSnapshot?.serviceName ?? '—'} />
          <Row k="时长" v={order.itemSnapshot?.durationMin ? `${order.itemSnapshot.durationMin} 分钟` : '—'} />
          <Row k="支付通道" v="本地 MOCK 通道（不产生真实扣款）" />
          {paymentNo && <Row k="paymentNo" v={paymentNo} mono />}
        </div>

        {err && <p className="mt-3 rounded-2xl bg-rose-50 px-3 py-2.5 text-[11px] leading-relaxed text-rose-600">{err}</p>}

        <div className="mt-5 space-y-2">
          {!paymentNo ? (
            <button
              type="button"
              disabled={busy}
              onClick={() => void prepay()}
              className={cx(
                'h-12 w-full rounded-2xl bg-btn-gradient text-sm font-semibold text-white shadow-btn',
                busy ? 'opacity-60' : 'active:scale-[0.99]',
              )}
            >
              {busy ? '处理中…' : '拉起支付'}
            </button>
          ) : (
            <>
              <button
                type="button"
                disabled={busy}
                onClick={() => void notify()}
                className={cx(
                  'h-12 w-full rounded-2xl bg-emerald-500 text-sm font-semibold text-white shadow-btn',
                  busy ? 'opacity-60' : 'active:scale-[0.99]',
                )}
              >
                {busy ? '回调中…' : '模拟渠道支付成功'}
              </button>
              <p className="text-[11px] leading-relaxed text-muted">
                回调会走真实事件链：订单转 PAID → 预约转 CONFIRMED；托管单还会自动预生成入住记录。
              </p>
            </>
          )}
          <button type="button" onClick={onClose} className="h-11 w-full rounded-2xl bg-warmgray text-xs font-semibold text-navy/70">
            稍后支付
          </button>
        </div>
      </div>
    </div>
  )
}

function Row({ k, v, mono }: { k: string; v: string; mono?: boolean }) {
  return (
    <div className="flex items-start justify-between gap-3">
      <span className="shrink-0 text-muted">{k}</span>
      <span className={cx('min-w-0 break-all text-right font-semibold text-ink', mono && 'font-mono text-[11px]')}>{v}</span>
    </div>
  )
}
