import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import Modal from '../ui/Modal'
import { IconCheckCircle } from '../ui/icons'
import { useToast } from '../ui/Toast'
import {
  appointmentsApi,
  CREATE_ERROR_TEXT,
  SERVICE_CATEGORY_LABEL,
  type AppointmentCreatedView,
  type AvailabilityView,
} from '../../api/appointments'
import type { CustomerPetRef } from '../../api/customers'
import type { StoreServiceItem } from '../../api/settings'
import { BizError } from '../../api/types'
import { fenToYuanText } from '../../api/dashboard'
import { cx } from '../../lib/cx'
import { addDaysISO, dateRange, durationText, nightsBetween, timeText, todayISO, weekdayOf } from '../../lib/dateText'
import { emptySlotsHint } from '../../lib/bookingHint'

/**
 * B 端「新增预约」（代客建单，居中弹窗）。
 *
 * 能力与 C 端建单页一一对齐（服务/宠物/日期时段/入住离店/指定员工/备注/幂等键/错误码人话表），
 * 只多一块"顾客只读"上下文、少一个"支付"动作——建单不代付（线下收款在订单侧登记/复核，V18），
 * 单据落 PENDING_PAYMENT 后由顾客在 C 端「订单」页支付。
 *
 * 契约（服务端已上线，ADR-059；权限 `appointment:create` 由 V10 授予店长/ADMIN，前台 V2 既有）：
 * - `GET  /store/store-services/bookable`（只读可订清单，闸 `appointment:create`；不要求价目管理权限）
 * - `GET  /store/store-services/{id}/availability?date&petId`（出参 = 后端 AvailabilityView）
 * - `POST /store/customers/{userId}/appointments`（body = AppointmentCreateRequest，source=RECEPTIONIST）
 *
 * 遛狗（DOG_WALK）走同一弹窗：服务下拉来自本店 ON 上架行，时段由服务端按营业时间计算；
 * 该服务 `requiredResourceType` 为空 ⇒ 不占台位，空时段的文案由 `emptySlotsHint` 按资源要求区分。
 */

const DATES = dateRange(14)
const INPUT = 'h-10 w-full rounded-lg bg-white px-3 text-sm text-ink ring-1 placeholder:text-muted focus:outline-none'
const INPUT_OK = `${INPUT} ring-line focus:ring-blue`
const NOTE = 'rounded-lg bg-[#FFF8E7] px-3 py-2.5 text-[11px] leading-relaxed text-[#8A6116]'

/** requestId 幂等键（Redis SETNX，TTL 5min）：同一组选择重试复用，改选择则换新值。 */
function newRequestId(): string {
  const rnd = globalThis.crypto?.randomUUID?.() ?? Math.random().toString(36).slice(2)
  return `b${Date.now().toString(36)}${rnd}`.slice(0, 64)
}

/** 时段显示：后端给 "HH:mm"（或带秒），统一截到分钟。 */
function slotLabel(t: string): string {
  return t.length > 5 ? t.slice(0, 5) : t
}

export default function BookAppointmentModal({
  customer,
  onClose,
}: {
  customer: { userId: string; nickname: string; phoneMasked: string; pets: CustomerPetRef[] }
  onClose: () => void
}) {
  return (
    <Modal
      open
      title="新增预约"
      description={`为 ${customer.nickname} 代客建单：单据记在该客户名下，顾客登录后可在 C 端看到并支付。`}
      onClose={onClose}
      width="max-w-[560px]"
    >
      <BookBody customer={customer} onClose={onClose} />
    </Modal>
  )
}

function BookBody({
  customer,
  onClose,
}: {
  customer: { userId: string; nickname: string; phoneMasked: string; pets: CustomerPetRef[] }
  onClose: () => void
}) {
  const { push } = useToast()
  const navigate = useNavigate()
  const [services, setServices] = useState<StoreServiceItem[] | null>(null)
  const [servicesErr, setServicesErr] = useState<string | null>(null)
  const [storeServiceId, setStoreServiceId] = useState<string | null>(null)
  const [petId, setPetId] = useState<string | null>(customer.pets[0]?.petId ?? null)
  const [date, setDate] = useState(todayISO())
  const [checkin, setCheckin] = useState(todayISO())
  const [checkout, setCheckout] = useState(addDaysISO(todayISO(), 2))
  const [avail, setAvail] = useState<AvailabilityView | null>(null)
  const [availLoading, setAvailLoading] = useState(false)
  const [availErr, setAvailErr] = useState<{ message: string; code: number } | null>(null)
  const [slotStart, setSlotStart] = useState<string | null>(null)
  const [employeeId, setEmployeeId] = useState<string | null>(null)
  const [remark, setRemark] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [created, setCreated] = useState<AppointmentCreatedView | null>(null)
  const reqId = useRef(newRequestId())

  useEffect(() => {
    let dead = false
    appointmentsApi.bookableServices().then(
      (p) => {
        if (!dead) setServices(p.list ?? [])
      },
      (e: unknown) => {
        if (dead) return
        setServices([])
        setServicesErr(e instanceof BizError ? e.message : e instanceof Error ? e.message : '服务列表加载失败')
      },
    )
    return () => {
      dead = true
    }
  }, [])

  const service = useMemo(() => services?.find((s) => s.id === storeServiceId) ?? null, [services, storeServiceId])
  const multiDay = (avail?.fulfillmentType ?? service?.fulfillmentType) === 'MULTI_DAY'
  const nights = multiDay ? nightsBetween(checkin, checkout) : null
  const availKey = storeServiceId && petId ? `${storeServiceId}|${multiDay ? checkin : date}|${petId}` : null

  // 可约查询：服务/日期/宠物任一变化即重拉，seq 丢弃陈旧响应（与 C 端建单页同构）
  const seq = useRef(0)
  useEffect(() => {
    if (!storeServiceId || !petId) {
      setAvail(null)
      setAvailErr(null)
      setAvailLoading(false)
      return
    }
    const my = ++seq.current
    setAvailLoading(true)
    setAvail(null)
    setAvailErr(null)
    setSlotStart(null)
    appointmentsApi
      .availability(storeServiceId, multiDay ? checkin : date, petId)
      .then((v) => {
        if (my !== seq.current) return
        setAvail(v)
        // 员工候选随服务/日期变化：上次选择仍在新候选里就保留，只有失效才回落"随机安排"
        setEmployeeId((cur) => (cur != null && v.employeeOptions.some((o) => o.id === cur) ? cur : null))
      })
      .catch((e: unknown) => {
        if (my !== seq.current) return
        setAvailErr(
          e instanceof BizError
            ? { message: e.message, code: e.code }
            : { message: e instanceof Error ? e.message : '加载失败', code: -1 },
        )
      })
      .finally(() => {
        if (my === seq.current) setAvailLoading(false)
      })
  }, [storeServiceId, petId, date, checkin, multiDay])

  // 选择变化即换 requestId，避免上一单的幂等键挡住这一单
  useEffect(() => {
    reqId.current = newRequestId()
  }, [availKey, employeeId])

  const slot = useMemo(() => avail?.slots.find((s) => s.start === slotStart) ?? null, [avail, slotStart])

  /** 不能提交的原因（按重要性排序）。按钮保持可点，点击时逐条告知，避免"静默置灰"的死胡同。 */
  const blockers = useMemo(() => {
    const list: string[] = []
    if (customer.pets.length === 0) list.push('该客户名下还没有宠物档案，先在「编辑资料 → 新增宠物」里建好再来')
    if (servicesErr) list.push('服务列表加载失败，无法建单')
    if (!storeServiceId) list.push('请先选择一项服务')
    if (!petId) list.push('请选择服务宠物')
    if (multiDay) {
      if (checkin >= checkout) list.push('离店日期必须晚于入住日期')
    } else if (!slot) list.push('请选择一个可约时段')
    return list
  }, [customer.pets.length, servicesErr, storeServiceId, petId, multiDay, checkin, checkout, slot])

  const submit = async () => {
    if (blockers.length > 0) {
      push(blockers[0], 'info')
      return
    }
    if (!petId || !storeServiceId) return
    setSubmitting(true)
    try {
      const res = await appointmentsApi.create(customer.userId, {
        requestId: reqId.current,
        petId,
        storeServiceId,
        employeeId,
        serviceDate: multiDay ? null : date,
        startTime: multiDay ? null : slotStart,
        boardingCheckinDate: multiDay ? checkin : null,
        boardingCheckoutDate: multiDay ? checkout : null,
        remark: remark.trim() || null,
      })
      setCreated(res)
      // 刻意**不在这里**触发 360 刷新：reload 会让 360 进入 loading 分支，把本弹窗连同成功视图
      // 一起卸载重建（表单清空、用户看不到预约号）——刷新统一放在关闭弹窗时（onClose 由调用方包装），
      // 成功反馈与"关后数据新鲜"两全。
    } catch (e) {
      const msg =
        e instanceof BizError
          ? CREATE_ERROR_TEXT[e.code] ?? e.message
          : e instanceof Error
            ? e.message
            : '提交失败，请重试'
      push(msg, 'error')
      if (e instanceof BizError && e.code === 10005) reqId.current = newRequestId()
    } finally {
      setSubmitting(false)
    }
  }

  if (created) {
    const exp = timeText(created.expireAt)
    return (
      <div className="flex flex-col gap-4">
        <div className="flex items-center gap-3">
          <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-[#E0F5E9] text-[#2FA96B]">
            <IconCheckCircle className="h-5 w-5" />
          </span>
          <div className="min-w-0">
            <p className="truncate text-sm font-black text-ink">{created.serviceNameSnapshot}</p>
            <p className="text-[11px] text-muted">
              预约号 <span className="font-mono text-ink">{created.appointmentNo}</span>
            </p>
          </div>
        </div>
        <dl className="flex flex-col gap-2 border-t border-line pt-3 text-xs">
          <ResultLine k="客户" v={`${customer.nickname}（${customer.phoneMasked}）`} />
          <ResultLine k="状态" v={created.status === 'PENDING_PAYMENT' ? '待支付' : created.status} />
          <ResultLine k="金额" v={fenToYuanText(created.amount)} strong />
          {nights && service ? <ResultLine k="计费" v={`${nights} 晚 × ${fenToYuanText(service.price)}/天`} /> : null}
          <ResultLine k="订单号" v={created.orderNo} mono />
          {exp ? <ResultLine k="支付时限" v={`${exp} 前（超时未支付将自动取消）`} /> : null}
        </dl>
        <p className={NOTE}>
          单据已记在顾客名下：顾客在 C 端登录后即可在「订单」页看到并完成支付。若顾客尚未设置过密码，可先用短信验证码登录，或走「忘记密码」认领账号。
        </p>
        <div className="flex justify-end gap-2.5 border-t border-line pt-3.5">
          <button
            type="button"
            onClick={() => {
              reqId.current = newRequestId()
              setCreated(null)
            }}
            className="h-9 rounded-lg border border-line px-3.5 text-xs font-semibold text-ink transition hover:border-blue hover:text-blue"
          >
            再约一单
          </button>
          <button
            type="button"
            onClick={() => {
              // 先关弹窗（调用方在 onClose 里刷新 360），再进入订单列表；新单是待支付，可在那里核对
              onClose()
              navigate('/orders')
            }}
            className="h-9 rounded-lg border border-line px-3.5 text-xs font-semibold text-ink transition hover:border-blue hover:text-blue"
          >
            查看订单
          </button>
          <button
            type="button"
            onClick={onClose}
            className="h-9 rounded-lg bg-blue px-4 text-xs font-semibold text-white transition hover:opacity-95"
          >
            完成
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4">
      {/* 顾客（只读，从客户 360 带入） */}
      <div className="flex items-center gap-2 rounded-xl bg-blue-mist/70 px-3 py-2.5 ring-1 ring-line">
        <span className="text-xs font-bold text-ink">{customer.nickname}</span>
        <span className="font-mono text-[11px] text-muted">{customer.phoneMasked}</span>
        <span className="ml-auto text-[11px] text-muted">本单记在该客户名下</span>
      </div>

      {servicesErr && <p className={NOTE}>服务列表加载失败：{servicesErr}</p>}

      {/* 服务 / 宠物 */}
      <div className="grid gap-3 sm:grid-cols-2">
        <label className="flex flex-col gap-1.5">
          <span className="text-xs font-semibold text-ink">服务</span>
          <select
            aria-label="服务"
            value={storeServiceId ?? ''}
            onChange={(e) => setStoreServiceId(e.target.value || null)}
            disabled={services === null || services.length === 0}
            className={cx(INPUT_OK, 'disabled:bg-blue-mist disabled:text-muted')}
          >
            <option value="" disabled>
              {services === null ? '服务加载中…' : services.length === 0 ? '无可订服务' : '选择服务…'}
            </option>
            {services?.map((s) => (
              <option key={s.id} value={s.id}>
                {s.serviceName ?? s.serviceCode ?? '未命名服务'}（
                {SERVICE_CATEGORY_LABEL[s.category ?? ''] ?? s.category ?? '未分类'}）
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1.5">
          <span className="text-xs font-semibold text-ink">宠物</span>
          <select
            aria-label="宠物"
            value={petId ?? ''}
            onChange={(e) => setPetId(e.target.value || null)}
            disabled={customer.pets.length === 0}
            className={cx(INPUT_OK, 'disabled:bg-blue-mist disabled:text-muted')}
          >
            {customer.pets.length === 0 ? (
              <option value="">该客户名下暂无宠物</option>
            ) : (
              customer.pets.map((p) => (
                <option key={p.petId} value={p.petId}>
                  {p.name}（{p.breed || p.species}）
                </option>
              ))
            )}
          </select>
        </label>
      </div>

      {service && (
        <p className="-mt-1 text-[11px] text-muted">
          {service.durationMin ? `约 ${durationText(service.durationMin)}` : '时长由门店安排'} ·{' '}
          {fenToYuanText(service.price)}
          {service.fulfillmentType === 'MULTI_DAY' ? '/天（按天计费）' : ''}
        </p>
      )}

      {/* 日期与时段 / 入住与离店 */}
      {!storeServiceId ? (
        <p className="rounded-xl bg-blue-mist/70 px-3 py-2.5 text-[11px] text-muted">先选一项服务，这里会显示可约日期与时段。</p>
      ) : (
        <div className="flex flex-col gap-3">
          {multiDay ? (
            <div className="grid grid-cols-2 gap-3">
              <label className="flex flex-col gap-1.5">
                <span className="text-xs font-semibold text-ink">入住日期</span>
                <input
                  type="date"
                  aria-label="入住日期"
                  value={checkin}
                  min={todayISO()}
                  onChange={(e) => setCheckin(e.target.value)}
                  className={INPUT_OK}
                />
              </label>
              <label className="flex flex-col gap-1.5">
                <span className="text-xs font-semibold text-ink">离店日期</span>
                <input
                  type="date"
                  aria-label="离店日期"
                  value={checkout}
                  min={addDaysISO(checkin, 1)}
                  onChange={(e) => setCheckout(e.target.value)}
                  className={INPUT_OK}
                />
              </label>
            </div>
          ) : (
            <div>
              <p className="mb-1.5 text-xs font-semibold text-ink">预约日期</p>
              <div className="flex gap-1.5 overflow-x-auto pb-1">
                {DATES.map((d) => {
                  const on = d === date
                  const [, m, dd] = d.split('-').map(Number)
                  return (
                    <button
                      key={d}
                      type="button"
                      aria-pressed={on}
                      onClick={() => setDate(d)}
                      className={cx(
                        'shrink-0 rounded-lg border px-2.5 py-1.5 text-center text-[11px] font-semibold transition',
                        on ? 'border-blue bg-blue text-white' : 'border-line bg-white text-ink hover:border-blue hover:text-blue',
                      )}
                    >
                      {m}/{dd}
                      <span className={cx('block text-[10px] font-normal', on ? 'text-white/80' : 'text-muted')}>
                        {weekdayOf(d)}
                      </span>
                    </button>
                  )
                })}
              </div>
            </div>
          )}

          {availLoading ? (
            <div className="grid grid-cols-3 gap-2 sm:grid-cols-4" aria-busy="true">
              {Array.from({ length: 8 }).map((_, i) => (
                <div key={i} className="h-10 animate-pulse rounded-lg bg-blue-mist" />
              ))}
            </div>
          ) : availErr ? (
            <p className={NOTE}>
              可用时段查询失败：{availErr.message}
              {availErr.code === 10004
                ? ' —— 该服务不属于当前门店或已下架，请刷新后重新选择。'
                : ''}
            </p>
          ) : multiDay ? (
            avail &&
            (avail.kennels.length === 0 ? (
              <p className={NOTE}>所选区间没有空闲笼位（服务端按体型从基准尺寸起允许升级分配），换日期试试。</p>
            ) : (
              <p className="rounded-lg bg-blue-mist px-3 py-2.5 text-[11px] text-ink ring-1 ring-line">
                区间内空闲笼位 {avail.kennels.length} 个（笼位由服务端按体型自动分配）
              </p>
            ))
          ) : avail && avail.slots.length > 0 ? (
            <div className="grid grid-cols-3 gap-2 sm:grid-cols-4">
              {avail.slots.map((s) => {
                const on = s.start === slotStart
                return (
                  <button
                    key={s.start}
                    type="button"
                    aria-pressed={on}
                    onClick={() => setSlotStart(s.start)}
                    className={cx(
                      'rounded-lg border px-2 py-2 text-center text-xs font-semibold transition',
                      on ? 'border-blue bg-blue text-white' : 'border-line bg-white text-ink hover:border-blue hover:text-blue',
                    )}
                  >
                    {slotLabel(s.start)}
                    <span className={cx('block text-[10px] font-normal', on ? 'text-white/80' : 'text-muted')}>
                      至 {slotLabel(s.end)}
                    </span>
                  </button>
                )
              })}
            </div>
          ) : (
            <p className={NOTE}>{emptySlotsHint(service?.requiredResourceType)}</p>
          )}
        </div>
      )}

      {/* 服务人员（可选，仅服务允许时出现） */}
      {avail?.allowUserPickEmployee && avail.employeeOptions.length > 0 && (
        <label className="flex flex-col gap-1.5">
          <span className="text-xs font-semibold text-ink">服务人员（可选）</span>
          <select
            aria-label="服务人员"
            value={employeeId ?? ''}
            onChange={(e) => setEmployeeId(e.target.value || null)}
            className={INPUT_OK}
          >
            <option value="">随机安排（由门店分配）</option>
            {avail.employeeOptions.map((o) => (
              <option key={o.id} value={o.id}>
                {o.name}
              </option>
            ))}
          </select>
        </label>
      )}

      {/* 备注 */}
      <label className="flex flex-col gap-1.5">
        <span className="flex items-center justify-between text-xs font-semibold text-ink">
          <span>备注（可选）</span>
          <span className="font-normal text-muted">{remark.length}/500</span>
        </span>
        <textarea
          aria-label="备注"
          value={remark}
          onChange={(e) => setRemark(e.target.value)}
          rows={2}
          maxLength={520}
          placeholder="如：怕吹风机，请温柔安抚"
          className="w-full resize-none rounded-lg bg-white px-3 py-2 text-sm leading-relaxed text-ink ring-1 ring-line placeholder:text-muted focus:outline-none focus:ring-blue"
        />
      </label>

      {/* 提交栏 */}
      <div className="flex items-center gap-2.5 border-t border-line pt-3.5">
        <div className="min-w-0 flex-1">
          <p className="truncate text-[11px] text-muted">
            {service ? service.serviceName ?? '未命名服务' : '未选服务'}
            {multiDay
              ? nights
                ? ` · ${nights} 晚`
                : ' · 日期待确认'
              : slot
                ? ` · ${slotLabel(slot.start)}-${slotLabel(slot.end)}`
                : ''}
          </p>
          <p className="text-sm font-black text-ink">
            {service
              ? multiDay && nights
                ? fenToYuanText(Number(service.price) * nights)
                : fenToYuanText(service.price)
              : '—'}
            {multiDay && nights ? <span className="ml-1 text-[10px] font-normal text-muted">预估</span> : null}
          </p>
        </div>
        <button
          type="button"
          onClick={onClose}
          className="h-9 shrink-0 rounded-lg border border-line px-3.5 text-xs font-semibold text-ink transition hover:border-blue hover:text-blue"
        >
          取消
        </button>
        <button
          type="button"
          disabled={submitting}
          onClick={() => void submit()}
          className={cx(
            'h-9 shrink-0 rounded-lg px-4 text-xs font-semibold text-white transition',
            submitting ? 'bg-blue/60' : blockers.length === 0 ? 'bg-blue hover:opacity-95' : 'bg-[#9DB6E8]',
          )}
        >
          {submitting ? '提交中…' : '确认建单'}
        </button>
      </div>
      <p className="-mt-2 text-[11px] leading-relaxed text-muted">
        {blockers.length > 0
          ? blockers[0]
          : '提交后生成待支付订单：顾客登录 C 端「订单」页完成支付，超时未支付自动取消。'}
      </p>
    </div>
  )
}

function ResultLine({ k, v, strong, mono }: { k: string; v: string; strong?: boolean; mono?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <dt className="shrink-0 text-muted">{k}</dt>
      <dd
        className={cx(
          'min-w-0 truncate text-right',
          strong ? 'text-sm font-black text-ink' : 'font-semibold text-ink',
          mono && 'font-mono text-[11px] font-normal',
        )}
      >
        {v}
      </dd>
    </div>
  )
}
