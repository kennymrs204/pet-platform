import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { appointmentApi } from '../api/appointment'
import { fenToYuan } from '../api/catalog'
import { OrderView, orderApi } from '../api/order'
import { petApi } from '../api/pet'
import { tokenStore } from '../auth/tokenStore'
import { useBizFail } from '../auth/useBizFail'
import BottomNav from '../components/layout/BottomNav'
import PageHeader from '../components/layout/PageHeader'
import PaySheet from '../components/order/PaySheet'
import CancelDialog from '../components/order/CancelDialog'
import PetAvatar from '../components/pet/PetAvatar'
import { IconCalendar, IconChevronRight } from '../components/ui/icons'
import { cx } from '../lib/cx'
import { countdownText } from '../lib/dateText'
import { usePublicStores } from '../lib/usePublicStores'
import { BoardRow, TABS, apptStatusTone, apptWhenText, APPT_STATUS_TEXT, buildRows, canCancel, mergeAppointments } from '../lib/orderBoard'

const PAGE_LIMIT = 100

/**
 * C 端「订单」页。列表以**预约行**为主档（只有它带 petId/日期/时段），
 * 订单行按 appointmentId 在本地 join 出金额与支付状态，宠物行按 petId 补齐名称头像；
 * 三者都是单次全量拉取（上限 100），触顶时页面顶部明示，避免"看起来是全部其实被截断"。
 */
export default function OrdersPage() {
  const navigate = useNavigate()
  const fail = useBizFail()
  const userId = tokenStore.getUserId()
  const [tab, setTab] = useState(TABS[0].key)
  const [rows, setRows] = useState<BoardRow[] | null>(null)
  const [capped, setCapped] = useState(false)
  const [paying, setPaying] = useState<OrderView | null>(null)
  const [cancelling, setCancelling] = useState<BoardRow | null>(null)
  const [, setTick] = useState(0)

  // 门店名解析（/public/stores，模块级缓存）；用 ref 持有，避免它变化时触发整表重拉
  const { nameOf } = usePublicStores()
  const nameOfRef = useRef(nameOf)
  nameOfRef.current = nameOf

  useEffect(() => {
    if (!userId) navigate('/login', { replace: true })
  }, [userId, navigate])

  const load = useCallback(
    async (tabKey: string, signal: AbortSignal) => {
      const conf = TABS.find((t) => t.key === tabKey) ?? TABS[0]
      const groups = await Promise.all(
        conf.statuses.length === 0
          ? [appointmentApi.list(undefined, 1, PAGE_LIMIT).then((p) => p.list ?? [])]
          : conf.statuses.map((s) => appointmentApi.list(s, 1, PAGE_LIMIT).then((p) => p.list ?? [])),
      )
      const [orders, pets] = await Promise.all([orderApi.list(undefined, PAGE_LIMIT), petApi.list()])
      if (signal.aborted) return
      const appts = mergeAppointments(groups)
      setRows(buildRows(appts, orders.list ?? [], pets ?? [], nameOfRef.current))
      setCapped(appts.length >= PAGE_LIMIT || (orders.total != null && Number(orders.total) >= PAGE_LIMIT))
    },
    [fail],
  )

  useEffect(() => {
    const ac = new AbortController()
    setRows(null)
    load(tab, ac.signal).catch((e) => {
      if (!ac.signal.aborted) fail(e)
    })
    return () => ac.abort()
  }, [tab, load, fail])

  const needCountdown = useMemo(() => rows?.some((r) => r.appt.status === 'PENDING_PAYMENT') ?? false, [rows])
  useEffect(() => {
    if (!needCountdown) return
    const t = window.setInterval(() => setTick((n) => n + 1), 1000)
    return () => window.clearInterval(t)
  }, [needCountdown])

  return (
    <main className="min-h-[100dvh] bg-brand-gradient">
      <div className="mx-auto max-w-md px-4 pb-32">
        <PageHeader title="我的订单" />

        <div className="flex gap-2 overflow-x-auto pb-1">
          {TABS.map((t) => {
            const on = t.key === tab
            return (
              <button
                key={t.key}
                type="button"
                onClick={() => setTab(t.key)}
                aria-current={on ? 'page' : undefined}
                className={cx(
                  'shrink-0 rounded-full px-3.5 py-1.5 text-xs font-semibold transition',
                  on ? 'bg-btn-gradient text-white shadow-btn' : 'bg-white/85 text-navy ring-1 ring-navy/5',
                )}
              >
                {t.label}
                {on && rows ? <span className="ml-1 font-normal opacity-80">{rows.length}</span> : null}
              </button>
            )
          })}
        </div>

        {capped && (
          <p className="mt-3 rounded-2xl border border-gold/40 bg-gold/10 px-3 py-2 text-[11px] leading-relaxed text-navy">
            当前接口单页上限 {PAGE_LIMIT} 条，列表可能未显示全部历史订单。
          </p>
        )}

        {rows === null ? (
          <div className="mt-3 space-y-3" aria-busy="true">
            <SkeletonRow />
            <SkeletonRow />
          </div>
        ) : rows.length === 0 ? (
          <div className="mt-6 rounded-3xl bg-white/85 p-8 text-center shadow-soft ring-1 ring-navy/5">
            <IconCalendar className="mx-auto h-8 w-8 text-gold-dark/50" />
            <p className="mt-3 text-sm font-semibold text-ink">这个页签下还没有订单</p>
            <p className="mt-1 text-xs text-muted">预约服务后会在这里看到进度；也可以切换其他页签看看。</p>
            <button
              type="button"
              onClick={() => navigate('/booking')}
              className="mt-4 inline-flex h-10 items-center rounded-2xl bg-btn-gradient px-5 text-sm font-semibold text-white shadow-btn"
            >
              去约服务
            </button>
          </div>
        ) : (
          <ul className="mt-3 space-y-3">
            {rows.map((r, i) => (
              <OrderRow
                key={r.appt.id}
                row={r}
                delay={i * 60}
                onPay={() => r.order && setPaying(r.order)}
                onOpen={() => navigate(`/orders/${r.appt.id}`)}
                onCancel={() => setCancelling(r)}
              />
            ))}
          </ul>
        )}

        <p className="mt-5 px-1 text-[11px] leading-relaxed text-muted">
          取消与改期均已接真实接口：未支付可改期（ADR-026，金额不变）；已付取消会进入退款审核，通过后退回原支付渠道（ADR-034）。退款进度需进入订单详情查看——列表接口不返回退款单，不为凑徽章而做 N+1 请求。
        </p>
      </div>

      {paying && (
        <PaySheet
          order={paying}
          onClose={() => setPaying(null)}
          onPaid={() => void load(tab, new AbortController().signal)}
        />
      )}
      {cancelling && (
        <CancelDialog
          appt={cancelling.appt}
          storeName={cancelling.storeName}
          onClose={() => setCancelling(null)}
          onCancelled={() => void load(tab, new AbortController().signal)}
        />
      )}
      <BottomNav active="orders" />
    </main>
  )
}

function OrderRow({
  row,
  delay,
  onPay,
  onOpen,
  onCancel,
}: {
  row: BoardRow
  delay: number
  onPay: () => void
  onOpen: () => void
  onCancel: () => void
}) {
  const { appt, order, pet, storeName } = row
  const payable = appt.status === 'PENDING_PAYMENT' && !!order
  const left = payable ? countdownText(appt.expireAt) : null
  return (
    <li className="animate-fade-up rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5" style={{ animationDelay: `${delay}ms` }}>
      <div className="flex items-start gap-3">
        {pet ? <PetAvatar pet={pet} className="h-12 w-12" /> : <NoPetAvatar />}
        <div className="min-w-0 flex-1">
          <p className="flex items-center gap-2">
            <span className="truncate text-sm font-bold text-ink">{appt.serviceNameSnapshot}</span>
            <span className={cx('shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold', apptStatusTone(appt.status))}>
              {APPT_STATUS_TEXT[appt.status] ?? appt.status}
            </span>
          </p>
          <p className="mt-1 truncate text-[11px] text-muted">
            {pet ? `${pet.name}${pet.breed ? ` · ${pet.breed}` : ''}` : '宠物已删除或不可见'} · {storeName}
          </p>
          <p className="mt-0.5 text-[11px] text-muted">{apptWhenText(appt)}</p>
        </div>
        <p className="shrink-0 font-mono text-sm font-bold text-navy">¥{fenToYuan(order?.amount ?? appt.priceSnapshot)}</p>
      </div>

      {payable && (
        <p className="mt-2.5 rounded-2xl bg-gold/10 px-3 py-2 text-[11px] text-navy">
          {left ? `请在 ${left} 内完成支付，超时自动取消并释放资源` : '支付已超时，稍后会被自动取消'}
        </p>
      )}

      <div className="mt-3 flex gap-2">
        {payable && (
          <button
            type="button"
            onClick={onPay}
            className="h-9 flex-1 rounded-xl bg-btn-gradient text-xs font-semibold text-white shadow-btn active:scale-[0.99]"
          >
            去支付
          </button>
        )}
        <button
          type="button"
          onClick={onOpen}
          className={cx(
            'flex h-9 items-center gap-0.5 rounded-xl px-4 text-xs font-semibold',
            payable ? 'bg-warmgray text-navy/80' : 'h-9 w-full bg-warmgray text-navy/80',
          )}
        >
          查看详情 <IconChevronRight className="h-3.5 w-3.5" />
        </button>
      </div>

      {canCancel(appt.status) && (
        <button
          type="button"
          onClick={onCancel}
          className="mt-2 w-full text-right text-[11px] font-semibold text-rose-500"
        >
          取消预约
        </button>
      )}
    </li>
  )
}

function NoPetAvatar() {
  return (
    <span className="grid h-12 w-12 shrink-0 place-items-center rounded-2xl bg-warmgray text-[10px] text-muted ring-1 ring-navy/5">
      无档案
    </span>
  )
}

function SkeletonRow() {
  return (
    <div className="animate-pulse rounded-3xl bg-white/70 p-4 ring-1 ring-navy/5" aria-hidden="true">
      <div className="flex gap-3">
        <div className="h-12 w-12 shrink-0 rounded-2xl bg-warmgray" />
        <div className="flex-1">
          <div className="h-4 w-2/5 rounded bg-warmgray" />
          <div className="mt-2 h-3 w-3/5 rounded bg-warmgray" />
          <div className="mt-2 h-3 w-1/3 rounded bg-warmgray" />
        </div>
        <div className="h-4 w-12 rounded bg-warmgray" />
      </div>
      <div className="mt-3 h-9 rounded-xl bg-warmgray" />
    </div>
  )
}
