import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AppointmentSelfView, AvailabilitySlot, CREATE_ERROR_TEXT, appointmentApi, buildModifyRequest } from '../../api/appointment'
import { BizError } from '../../api/types'
import { fenToYuan } from '../../api/catalog'
import DateStrip from '../booking/DateStrip'
import SlotGrid from '../booking/SlotGrid'
import { useToast } from '../ui/Toast'
import { errText } from '../../auth/useBizFail'
import { addDaysISO, dateRange, dateText, nightsBetween, todayISO } from '../../lib/dateText'
import { cx } from '../../lib/cx'

const DAYS = dateRange(14)

function newRequestId(): string {
  const rnd = globalThis.crypto?.randomUUID?.() ?? Math.random().toString(36).slice(2)
  return `md${Date.now().toString(36)}${rnd}`.slice(0, 64)
}

/**
 * 改期面板（ADR-026：仅 PENDING_PAYMENT 可改，纯排期动作——不涉金额、不涉退款、不涉履约）。
 *
 * 三条必须在前端守住的契约事实：
 *  1. INSTANT 的 `serviceDate`+`startTime` 成对必填；MULTI_DAY 的入住/离店成对，`serviceDate` 由后端联动=入住日；
 *  2. **不能改服务与宠物**（换服务=取消+重订；改 storeServiceId 会牵动价格快照与资源类型）；
 *  3. `updateModifyInstant` 无条件写 `employee_id = #{employeeId}`——**不传即清空已分配员工**，
 *     所以默认回传原 employeeId，「改为随机安排」必须是用户的显式动作。
 * 成功后后端重置 `expire_at`（支付时限跟随最新槽位），故这里提示时限已重置并交回调用方刷新。
 */
export default function ReschedulePanel({
  appt,
  onModified,
  onError,
  onClose,
}: {
  appt: AppointmentSelfView
  onModified: (next: AppointmentSelfView) => void
  onError: (e: unknown) => void
  onClose: () => void
}) {
  const { push } = useToast()
  const multiDay = !!appt.boardingCheckinDate
  const [date, setDate] = useState(appt.serviceDate ?? todayISO())
  const [checkin, setCheckin] = useState(appt.boardingCheckinDate ?? todayISO())
  const [checkout, setCheckout] = useState(appt.boardingCheckoutDate ?? addDaysISO(todayISO(), 2))
  const [avail, setAvail] = useState<AvailabilitySlot[] | null>(null)
  const [loading, setLoading] = useState(false)
  const [slotStart, setSlotStart] = useState<string | null>(appt.startTime)
  const [keepEmployee, setKeepEmployee] = useState(true)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  const reqId = useRef(newRequestId())
  const seq = useRef(0)

  const queryDate = multiDay ? checkin : date

  const load = useCallback(() => {
    const my = ++seq.current
    setLoading(true)
    setAvail(null)
    setSlotStart(null)
    appointmentApi
      .availability(String(appt.storeServiceId), queryDate, String(appt.petId))
      .then((v) => {
        if (my === seq.current) setAvail(v.slots ?? [])
      })
      .catch((e) => {
        if (my === seq.current) onError(e)
      })
      .finally(() => {
        if (my === seq.current) setLoading(false)
      })
  }, [appt.storeServiceId, appt.petId, queryDate, onError])

  useEffect(() => {
    load()
  }, [load])

  const nights = useMemo(() => (multiDay ? nightsBetween(checkin, checkout) : null), [multiDay, checkin, checkout])
  const blocked = multiDay ? !nights : !slotStart

  const submit = async () => {
    if (blocked) {
      push(multiDay ? '离店日期必须晚于入住日期' : '请选择新的可约时段', 'info')
      return
    }
    setBusy(true)
    setErr('')
    try {
      const next = await appointmentApi.modify(
        appt.id,
        buildModifyRequest(appt, {
          requestId: reqId.current,
          date,
          startTime: slotStart,
          checkin,
          checkout,
          keepEmployee,
        }),
      )
      push('改期成功，支付时限已重新计时', 'success')
      onModified(next)
    } catch (e) {
      setErr(e instanceof BizError ? CREATE_ERROR_TEXT[e.code] ?? errText(e) : errText(e))
      if (e instanceof BizError && e.code === 10005) reqId.current = newRequestId()
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="animate-fade-up mt-4 rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-bold text-ink">改期</h2>
        <button type="button" onClick={onClose} className="text-[11px] font-semibold text-muted">
          收起
        </button>
      </div>
      <p className="mt-1.5 text-[11px] leading-relaxed text-muted">
        只能改时间：{multiDay ? '入住与离店区间' : '日期与时段'}。服务与宠物不可更换（需要换请取消后重新预约）。
        金额不变，改成功后支付时限重新计时。
      </p>

      {multiDay ? (
        <div className="mt-3 grid grid-cols-2 gap-3">
          <label className="block">
            <span className="mb-1 block px-1 text-[11px] font-semibold text-navy/70">新入住日期</span>
            <input
              type="date"
              value={checkin}
              min={todayISO()}
              onChange={(e) => setCheckin(e.target.value)}
              className="w-full rounded-2xl bg-white px-3 py-2.5 text-sm text-ink ring-1 ring-navy/5 outline-none"
            />
          </label>
          <label className="block">
            <span className="mb-1 block px-1 text-[11px] font-semibold text-navy/70">新离店日期</span>
            <input
              type="date"
              value={checkout}
              min={addDaysISO(checkin, 1)}
              onChange={(e) => setCheckout(e.target.value)}
              className="w-full rounded-2xl bg-white px-3 py-2.5 text-sm text-ink ring-1 ring-navy/5 outline-none"
            />
          </label>
          <p className="col-span-2 text-[11px] text-muted">
            {nights && nights >= 1 ? `新区间共 ${nights} 晚 · 金额仍为 ¥${fenToYuan(appt.priceSnapshot)}` : '离店必须晚于入住'}
          </p>
        </div>
      ) : (
        <>
          <div className="mt-3">
            <DateStrip days={DAYS} value={date} onChange={setDate} today={todayISO()} />
          </div>
          <div className="mt-3">
            {loading ? (
              <div className="grid grid-cols-3 gap-2" aria-busy="true">
                {Array.from({ length: 6 }).map((_, i) => (
                  <div key={i} className="h-10 animate-pulse rounded-2xl bg-warmgray" />
                ))}
              </div>
            ) : avail && avail.length > 0 ? (
              <SlotGrid slots={avail} value={slotStart} onChange={setSlotStart} />
            ) : (
              <p className="rounded-2xl bg-warmgray px-3 py-2.5 text-[11px] leading-relaxed text-muted">
                {dateText(date)} 没有可约时段，换一天试试。
              </p>
            )}
          </div>
          {appt.employeeId != null && (
            <div className="mt-3 flex items-center gap-2 rounded-2xl bg-ivory px-3 py-2.5 ring-1 ring-gold/25">
              <span className="min-w-0 flex-1 text-[11px] leading-relaxed text-navy/80">
                本单已分配服务人员（员工 {appt.employeeId}）。保留则新时段需该员工排得开；改期后也可让门店重新分配。
              </span>
              <button
                type="button"
                onClick={() => setKeepEmployee((v) => !v)}
                className={cx(
                  'shrink-0 rounded-xl px-2.5 py-1.5 text-[11px] font-semibold',
                  keepEmployee ? 'bg-btn-gradient text-white' : 'bg-white text-navy/70 ring-1 ring-navy/10',
                )}
              >
                {keepEmployee ? '保留原员工' : '改为随机安排'}
              </button>
            </div>
          )}
        </>
      )}

      {err && <p className="mt-3 rounded-2xl bg-rose-50 px-3 py-2.5 text-[11px] leading-relaxed text-rose-600">{err}</p>}

      <div className="mt-4 flex gap-2">
        <button type="button" onClick={onClose} className="h-11 w-24 rounded-2xl bg-warmgray text-xs font-semibold text-navy/80">
          取消
        </button>
        <button
          type="button"
          disabled={busy}
          onClick={() => void submit()}
          className={cx(
            'h-11 flex-1 rounded-2xl bg-btn-gradient text-xs font-semibold text-white shadow-btn',
            busy ? 'opacity-60' : 'active:scale-[0.99]',
          )}
        >
          {busy ? '提交中…' : '确认改期'}
        </button>
      </div>
    </section>
  )
}
