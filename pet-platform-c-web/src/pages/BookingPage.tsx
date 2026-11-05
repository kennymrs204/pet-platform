import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { AppointmentCreatedView, AvailabilityView, CREATE_ERROR_TEXT, appointmentApi } from '../api/appointment'
import { StoreServiceVO, catalogApi, fenToYuan, totalFen } from '../api/catalog'
import { usePublicStores } from '../lib/usePublicStores'
import { BizError } from '../api/types'
import { PetVO, petApi } from '../api/pet'
import { tokenStore } from '../auth/tokenStore'
import { errText, useBizFail } from '../auth/useBizFail'
import BottomNav from '../components/layout/BottomNav'
import PageHeader from '../components/layout/PageHeader'
import DateStrip from '../components/booking/DateStrip'
import SlotGrid from '../components/booking/SlotGrid'
import PetPicker from '../components/pet/PetPicker'
import { useToast } from '../components/ui/Toast'
import { IconCheck, IconPaw, IconPlus } from '../components/ui/icons'
import { cx } from '../lib/cx'
import { addDaysISO, countdownText, dateRange, dateText, durationText, nightsBetween, todayISO } from '../lib/dateText'
import { STATUS_TEXT } from '../lib/petText'

const DATES = dateRange(14)
const CAT_LABEL: Record<string, string> = {
  GROOMING: '洗护',
  MEDICAL: '医疗',
  BOARDING: '寄养',
  TRAINING: '训练',
  WALK: '遛狗',
}

/** requestId 幂等键（Redis SETNX，TTL 5min）：同一组选择重试复用，改选择则换新值。 */
function newRequestId(): string {
  const rnd = globalThis.crypto?.randomUUID?.() ?? Math.random().toString(36).slice(2)
  return `c${Date.now().toString(36)}${rnd}`.slice(0, 64)
}

/**
 * C 端「服务」页＝预约下单黄金路径（ADR-016）：选门店 → 选服务 → 选宠物 → 选日期时段 → 确认创建。
 * 数据口径分两层，互不混淆：
 *  - 门店列表走 lib/usePublicStores（GET /public/stores，ADR-022 已上线；请求失败才回退演示数据并黄条明示）；
 *  - 服务、宠物、可约时段、创建预约全部走已上线的真实接口。
 * 托管（MULTI_DAY）用入住/离店日期替代时段，笼位由服务端按体型分配，前端只展示当日空闲笼位。
 */
export default function BookingPage() {
  const navigate = useNavigate()
  const { push } = useToast()
  const fail = useBizFail()
  const [params] = useSearchParams()
  const userId = tokenStore.getUserId()

  const { stores, demo: storesDemo, loading: storesLoading } = usePublicStores()
  const [storeId, setStoreId] = useState(() => params.get('storeId') ?? '')
  const [services, setServices] = useState<StoreServiceVO[] | null>(null)
  const [pets, setPets] = useState<PetVO[] | null>(null)
  const [storeServiceId, setStoreServiceId] = useState<string | null>(null)
  const [petId, setPetId] = useState<string | null>(params.get('petId'))
  const [date, setDate] = useState(todayISO())
  const [checkin, setCheckin] = useState(todayISO())
  const [checkout, setCheckout] = useState(addDaysISO(todayISO(), 2))
  const [avail, setAvail] = useState<AvailabilityView | null>(null)
  const [availLoading, setAvailLoading] = useState(false)
  const [slotStart, setSlotStart] = useState<string | null>(null)
  const [employeeId, setEmployeeId] = useState<string | null>(null)
  const [remark, setRemark] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [created, setCreated] = useState<AppointmentCreatedView | null>(null)
  const reqId = useRef(newRequestId())

  useEffect(() => {
    if (!userId) navigate('/login', { replace: true })
  }, [userId, navigate])

  // 门店列表到位后再定默认门店：深链带进来的 storeId 若不在列表里（或还没到），回落到第一家
  useEffect(() => {
    if (storesLoading || stores.length === 0) return
    setStoreId((cur) => (cur && stores.some((s) => String(s.id) === cur) ? cur : String(stores[0].id)))
  }, [storesLoading, stores])

  // 服务列表：换门店重拉
  useEffect(() => {
    const ac = new AbortController()
    setServices(null)
    setStoreServiceId(null)
    if (!storeId) return () => ac.abort()
    catalogApi
      .listStoreServices(storeId, { pageSize: 50, signal: ac.signal })
      .then((p) => {
        if (!ac.signal.aborted) setServices(p.list ?? [])
      })
      .catch((e) => {
        if (ac.signal.aborted) return
        setServices([])
        fail(e)
      })
    return () => ac.abort()
  }, [storeId, fail])

  // 宠物列表：进页拉一次，够用于选择器
  useEffect(() => {
    const ac = new AbortController()
    petApi
      .list()
      .then((d) => {
        if (ac.signal.aborted) return
        const list = Array.isArray(d) ? d : []
        setPets(list)
        const usable = list.find((p) => p.status === 'NORMAL') ?? list[0]
        // 深链带来的 petId 优先；仅在无选中时兜底选一只
        if (usable) setPetId((cur) => (cur && list.some((p) => p.id === cur) ? cur : usable.id))
      })
      .catch((e) => {
        if (!ac.signal.aborted) {
          setPets([])
          fail(e)
        }
      })
    return () => ac.abort()
  }, [fail])

  const service = useMemo(
    () => services?.find((s) => s.id === storeServiceId) ?? null,
    [services, storeServiceId],
  )
  const pet = useMemo(() => pets?.find((p) => p.id === petId) ?? null, [pets, petId])
  const multiDay = (avail?.fulfillmentType ?? service?.fulfillmentType) === 'MULTI_DAY'
  /** 托管晚数（ADR-025 计价口径 = checkout − checkin）。此处只做下单前预估，最终金额以后端返回为准。 */
  const nights = multiDay ? nightsBetween(checkin, checkout) : null

  // 可约查询：服务/日期/宠物任一变化即重拉，seq 丢弃陈旧响应
  const availKey = storeServiceId && petId ? `${storeServiceId}|${multiDay ? checkin : date}|${petId}` : null
  const seq = useRef(0)
  useEffect(() => {
    if (!storeServiceId) {
      setAvail(null)
      return
    }
    const my = ++seq.current
    const ac = new AbortController()
    setAvailLoading(true)
    setAvail(null)
    setSlotStart(null)
    appointmentApi
      .availability(storeServiceId, multiDay ? checkin : date, petId ?? undefined)
      .then((v) => {
        if (my !== seq.current) return
        setAvail(v)
        // 员工候选随服务/日期变化：上一次的选择若仍在新候选里就**保留**，
        // 只有失效（候选里没有/本服务不可选人）才回落"随机安排"。
        // 曾是无脑 setEmployeeId(null)：用户先点员工再调日期，选择被静默清空，
        // 提交成"随机安排"的单——顾客以为指定了人，员工却永远看不到这单（实测踩坑）。
        setEmployeeId((cur) => (cur != null && v.employeeOptions.some((o) => o.id === cur) ? cur : null))
      })
      .catch((e) => {
        if (my !== seq.current || ac.signal.aborted) return
        push(errText(e), 'error')
      })
      .finally(() => {
        if (my === seq.current) setAvailLoading(false)
      })
    return () => ac.abort()
  }, [storeServiceId, petId, date, checkin, multiDay, push])

  // 选择变化即换 requestId，避免上一单的幂等键挡住这一单
  useEffect(() => {
    reqId.current = newRequestId()
  }, [availKey, employeeId])

  const slot = useMemo(() => avail?.slots.find((s) => s.start === slotStart) ?? null, [avail, slotStart])

  /** 不能提交的原因（按重要性排序）。按钮保持可点，点击时逐条告知，避免"静默置灰"的死胡同。 */
  const blockers = useMemo(() => {
    const list: string[] = []
    if (!storeServiceId) list.push('请先选择一项服务')
    if (!petId) list.push('请选择服务宠物')
    else if (pet && pet.status !== 'NORMAL') list.push('该宠物当前状态不可预约，请先在档案里改为正常')
    if (multiDay) {
      if (checkin >= checkout) list.push('离店日期必须晚于入住日期')
    } else if (!slot) list.push('请选择一个可约时段')
    return list
  }, [storeServiceId, petId, pet, multiDay, checkin, checkout, slot])

  const submit = async () => {
    if (blockers.length > 0) {
      push(blockers[0], 'info')
      return
    }
    if (!petId || !storeServiceId) {
      push('请先选择服务与宠物', 'info')
      return
    }
    setSubmitting(true)
    try {
      const res = await appointmentApi.create({
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
    } catch (e) {
      push(e instanceof BizError ? CREATE_ERROR_TEXT[e.code] ?? errText(e) : errText(e), 'error')
      if (e instanceof BizError && e.code === 10005) reqId.current = newRequestId()
    } finally {
      setSubmitting(false)
    }
  }

  if (created)
    return (
      <CreatedResult
        created={created}
        nights={multiDay ? nights : null}
        unitFen={service?.price}
        onAgain={() => setCreated(null)}
        onHome={() => navigate('/home')}
      />
    )

  return (
    <main className="min-h-[100dvh] bg-brand-gradient">
      <div className="mx-auto max-w-md px-4 pb-48">
        <PageHeader title="预约服务" />

        <p className="animate-fade-up mt-1 text-xs text-muted">为毛孩子安排一段安心、专业的照顾时光</p>

        {storesDemo && (
          <p className="mt-4 rounded-2xl border border-gold/40 bg-gold/10 px-3 py-2 text-[11px] leading-relaxed text-navy">
            门店公开接口（GET /public/stores）本次请求失败，门店列表已回退本地演示数据；服务、时段与下单仍走真实接口。
          </p>
        )}

        {/* 门店 */}
        <Section title="选择门店" delay={60}>
          {storesLoading ? (
            <div className="h-12 animate-pulse rounded-2xl bg-white/60" aria-busy="true" />
          ) : (
            <div className="flex gap-2 overflow-x-auto pb-1">
              {stores.map((s) => {
                const on = String(s.id) === storeId
                return (
                  <button
                    key={s.id}
                    type="button"
                    onClick={() => setStoreId(String(s.id))}
                    className={cx(
                      'shrink-0 rounded-2xl px-3.5 py-2 text-left text-xs font-semibold transition',
                      on ? 'bg-btn-gradient text-white shadow-btn' : 'bg-white/85 text-navy ring-1 ring-navy/5 hover:bg-white',
                    )}
                  >
                    {s.name}
                    <span className={cx('mt-0.5 block text-[10px] font-normal', on ? 'text-white/75' : 'text-muted')}>
                      {s.address}
                    </span>
                  </button>
                )
              })}
            </div>
          )}
        </Section>

        {/* 服务 */}
        <Section title="选择服务" delay={120}>
          {services === null ? (
            <div className="space-y-2" aria-busy="true">
              <div className="h-16 animate-pulse rounded-2xl bg-white/60" />
              <div className="h-16 animate-pulse rounded-2xl bg-white/60" />
            </div>
          ) : services.length === 0 ? (
            <Hint>该门店暂未上架服务（只显示 status=ON 的价目表行），换一家门店试试。</Hint>
          ) : (
            <div className="space-y-2">
              {services.map((s) => {
                const on = s.id === storeServiceId
                return (
                  <button
                    key={s.id}
                    type="button"
                    onClick={() => setStoreServiceId(s.id)}
                    className={cx(
                      'flex w-full items-start gap-3 rounded-2xl p-3 text-left transition',
                      on ? 'bg-btn-gradient text-white shadow-btn' : 'bg-white/85 ring-1 ring-navy/5 hover:bg-white',
                    )}
                  >
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center gap-1.5">
                        <span className="truncate text-sm font-bold">{s.serviceName}</span>
                        <span
                          className={cx(
                            'shrink-0 rounded-md px-1.5 py-0.5 text-[10px] font-medium',
                            on ? 'bg-white/20 text-white' : 'bg-ivory text-gold-dark ring-1 ring-gold/30',
                          )}
                        >
                          {CAT_LABEL[s.category] ?? s.category}
                        </span>
                        {s.fulfillmentType === 'MULTI_DAY' && (
                          <span className={cx('shrink-0 text-[10px]', on ? 'text-white/80' : 'text-muted')}>按天计费</span>
                        )}
                      </span>
                      {s.description && (
                        <span className={cx('mt-1 block truncate text-[11px]', on ? 'text-white/75' : 'text-muted')}>
                          {s.description}
                        </span>
                      )}
                      <span className={cx('mt-1 block text-[11px]', on ? 'text-white/80' : 'text-muted')}>
                        {s.durationMin ? `约 ${durationText(s.durationMin)}` : '时长由门店安排'}
                      </span>
                    </span>
                    <span className="shrink-0 text-right">
                      <span className={cx('font-mono text-sm font-bold', on ? 'text-gold-light' : 'text-navy')}>
                        ¥{fenToYuan(s.price)}
                        {s.fulfillmentType === 'MULTI_DAY' ? '/天' : ''}
                      </span>
                      {on && <IconCheck className="ml-auto mt-1.5 h-4 w-4" />}
                    </span>
                  </button>
                )
              })}
            </div>
          )}
        </Section>

        {/* 宠物 */}
        <Section title="服务宠物" delay={180}>
          {pets === null ? (
            <div className="h-12 animate-pulse rounded-2xl bg-white/60" aria-busy="true" />
          ) : pets.length === 0 ? (
            <div className="rounded-2xl bg-white/85 p-4 text-center ring-1 ring-navy/5">
              <IconPaw className="mx-auto h-7 w-7 text-gold-dark/50" />
              <p className="mt-2 text-xs text-muted">下单前需要先有宠物档案</p>
              <button
                type="button"
                onClick={() => navigate('/pets/new')}
                className="mt-2.5 inline-flex h-9 items-center gap-1 rounded-xl bg-btn-gradient px-4 text-xs font-semibold text-white shadow-btn"
              >
                <IconPlus className="h-3.5 w-3.5" /> 添加宠物
              </button>
            </div>
          ) : (
            <PetPicker pets={pets} value={petId} onChange={setPetId} onAdd={() => navigate('/pets/new')} />
          )}
          {pet && pet.status !== 'NORMAL' && (
            <Hint>这只宠物当前状态为「{STATUS_TEXT[pet.status]}」，后端会拒绝下单（20102），换一只或先改档案。</Hint>
          )}
          {multiDay && pet && !pet.sizeCategory && <Hint>托管需要按体型分配笼位，这只宠物还没录体重，先去档案补上。</Hint>}
        </Section>

        {/* 日期与时段 */}
        <Section title={multiDay ? '入住与离店' : '预约日期与时段'} delay={240}>
          {multiDay ? (
            <div className="grid grid-cols-2 gap-3">
              <label className="block">
                <span className="mb-1 block px-1 text-[11px] font-semibold text-navy/70">入住日期</span>
                <input
                  type="date"
                  value={checkin}
                  min={todayISO()}
                  onChange={(e) => setCheckin(e.target.value)}
                  className="w-full rounded-2xl bg-white/90 px-3 py-2.5 text-sm text-ink ring-1 ring-navy/5 outline-none"
                />
              </label>
              <label className="block">
                <span className="mb-1 block px-1 text-[11px] font-semibold text-navy/70">离店日期</span>
                <input
                  type="date"
                  value={checkout}
                  min={addDaysISO(checkin, 1)}
                  onChange={(e) => setCheckout(e.target.value)}
                  className="w-full rounded-2xl bg-white/90 px-3 py-2.5 text-sm text-ink ring-1 ring-navy/5 outline-none"
                />
              </label>
              {checkin >= checkout && <Hint>离店必须晚于入住（后端按跨天区间分配笼位）。</Hint>}
            </div>
          ) : (
            <DateStrip days={DATES} value={date} onChange={setDate} today={todayISO()} />
          )}

          <div className="mt-3">
            {!storeServiceId ? (
              <Hint>先选一项服务，这里才会显示可约时段。</Hint>
            ) : availLoading ? (
              <div className="grid grid-cols-3 gap-2" aria-busy="true">
                {Array.from({ length: 6 }).map((_, i) => (
                  <div key={i} className="h-10 animate-pulse rounded-2xl bg-white/60" />
                ))}
              </div>
            ) : multiDay ? (
              <KennelList avail={avail} />
            ) : avail && avail.slots.length > 0 ? (
              <>
                <SlotGrid slots={avail.slots} value={slotStart} onChange={setSlotStart} />
                <p className="mt-2 text-[11px] text-muted">
                  时段为服务端按营业时间、休息段、资源占用与提前量算出的可约窗口（30 分钟滑窗）。
                </p>
              </>
            ) : (
              <Hint>
                {dateText(multiDay ? checkin : date)} 该服务没有可约时段：要么当天资源已被占满，要么门店还没登记这类资源台账
                （洗澡要洗浴台、诊疗要诊室、美容不占资源）。可以换一天、换一项服务，或切到另一家门店试试。
              </Hint>
            )}
          </div>
        </Section>

        {/* 员工 */}
        {avail && avail.allowUserPickEmployee && avail.employeeOptions.length > 0 && (
          <Section title="偏好服务人员（可选）" delay={300}>
            <div className="space-y-2">
              <button
                type="button"
                onClick={() => setEmployeeId(null)}
                className={cx(
                  'w-full rounded-2xl px-3.5 py-2.5 text-left text-xs font-semibold transition',
                  employeeId === null ? 'bg-btn-gradient text-white shadow-btn' : 'bg-white/85 text-navy ring-1 ring-navy/5',
                )}
              >
                随机安排（由门店分配）
              </button>
              {avail.employeeOptions.map((e) => {
                const on = employeeId === e.id
                return (
                  <button
                    key={e.id}
                    type="button"
                    onClick={() => setEmployeeId(e.id)}
                    className={cx(
                      'w-full rounded-2xl px-3.5 py-2.5 text-left text-xs font-semibold transition',
                      on ? 'bg-btn-gradient text-white shadow-btn' : 'bg-white/85 text-navy ring-1 ring-navy/5',
                    )}
                  >
                    {e.name}
                  </button>
                )
              })}
            </div>
            <p className="mt-2 text-[11px] text-muted">仅展示当日该门店可服务的人员；不选则由门店分配。</p>
          </Section>
        )}

        {/* 备注 */}
        <Section title="备注" delay={360}>
          <textarea
            value={remark}
            onChange={(e) => setRemark(e.target.value)}
            rows={3}
            maxLength={520}
            placeholder="如：怕吹风机，请温柔安抚"
            className="w-full resize-none rounded-2xl bg-white/90 px-3.5 py-2.5 text-sm leading-relaxed text-ink shadow-soft ring-1 ring-navy/5 outline-none focus:ring-2 focus:ring-navy/25"
          />
          <p className="mt-1 text-right text-[11px] text-muted">{remark.length}/500</p>
        </Section>
      </div>

      {/* 底部提交栏 */}
      <div className="fixed inset-x-0 bottom-[68px] z-30 border-t border-navy/5 bg-white/92 px-4 py-3 shadow-card backdrop-blur-xl">
        <div className="mx-auto flex max-w-md items-center gap-3">
          <div className="min-w-0 flex-1">
            <p className="truncate text-[11px] text-muted">
              {service ? service.serviceName : '未选服务'}
              {pet ? ` · ${pet.name}` : ''}
              {multiDay ? (nights ? ` ${dateText(checkin)} 至 ${dateText(checkout)} · ${nights} 晚` : ' 日期待确认') : slot ? ` ${slot.start}-${slot.end}` : ''}
            </p>
            <p className="font-mono text-lg font-bold text-navy">
              ¥{service ? fenToYuan(multiDay && nights ? totalFen(service.price, nights) : service.price) : '—'}
              {multiDay && nights ? <span className="ml-1 text-[10px] font-normal text-muted">预估</span> : null}
            </p>
          </div>
          <button
            type="button"
            disabled={submitting}
            onClick={() => void submit()}
            className={cx(
              'h-12 w-40 rounded-2xl text-sm font-semibold text-white transition',
              submitting
                ? 'bg-btn-gradient opacity-60'
                : blockers.length === 0
                  ? 'bg-btn-gradient shadow-btn hover:opacity-95 active:scale-[0.99]'
                  : 'bg-navy/30',
            )}
          >
            {submitting ? '提交中…' : '确认预约'}
          </button>
        </div>
        <p className="mx-auto mt-1 max-w-md text-[10px] text-muted">
          {blockers.length > 0 ? `${blockers[0]}（还差 ${blockers.length} 项）` : '提交后为你保留支付时间，超时自动取消；支付入口在订单页。'}
        </p>
      </div>
      <BottomNav active="service" />
    </main>
  )
}

function KennelList({ avail }: { avail: AvailabilityView | null }) {
  if (!avail) return null
  if (avail.kennels.length === 0) {
    return <Hint>所选区间没有空闲笼位（后端按体型从基准尺寸起允许升级分配），换日期试试。</Hint>
  }
  return (
    <div className="rounded-2xl bg-white/85 p-3 ring-1 ring-navy/5">
      <p className="text-[11px] font-semibold text-navy/80">
        区间内空闲笼位 {avail.kennels.length} 个（具体分配由服务端按体型判定）
      </p>
      <ul className="mt-1.5 flex flex-wrap gap-1.5">
        {avail.kennels.slice(0, 8).map((k) => (
          <li key={k.id} className="rounded-full bg-ivory px-2.5 py-1 text-[11px] text-gold-dark ring-1 ring-gold/25">
            {k.name || k.type}
          </li>
        ))}
      </ul>
    </div>
  )
}

/** 创建成功结果页：展示预约号、待支付倒计时与订单号；支付动作属订单页（下一个交付）。 */
function CreatedResult({
  created,
  nights,
  unitFen,
  onAgain,
  onHome,
}: {
  created: AppointmentCreatedView
  nights: number | null
  unitFen?: string | number
  onAgain: () => void
  onHome: () => void
}) {
  const [, tick] = useState(0)
  useEffect(() => {
    const t = window.setInterval(() => tick((n) => n + 1), 1000)
    return () => window.clearInterval(t)
  }, [])
  const left = countdownText(created.expireAt)
  return (
    <main className="min-h-[100dvh] bg-brand-gradient">
      <div className="mx-auto max-w-md px-4 pb-10">
        <PageHeader title="预约已创建" fallback="/home" />
        <section className="animate-fade-up mt-2 rounded-3xl bg-white/90 p-5 shadow-soft ring-1 ring-navy/5">
          <span className="grid h-12 w-12 place-items-center rounded-2xl bg-emerald-50 text-emerald-600 ring-1 ring-emerald-200/70">
            <IconCheck className="h-6 w-6" />
          </span>
          <h2 className="mt-3 text-base font-bold text-ink">{created.serviceNameSnapshot}</h2>
          <p className="mt-1 text-xs text-muted">
            预约号 <span className="font-mono text-navy/80">{created.appointmentNo}</span>
          </p>

          <dl className="mt-4 space-y-2.5 border-t border-navy/5 pt-4 text-xs">
            <Line k="状态" v={created.status === 'PENDING_PAYMENT' ? '待支付' : created.status} />
            <Line k="金额" v={`¥${fenToYuan(created.amount)}`} strong />
            {nights && unitFen != null ? <Line k="计费" v={`${nights} 晚 × ¥${fenToYuan(unitFen)}/天`} /> : null}
            <Line k="订单号" v={created.orderNo} mono />
            {created.durationSnapshot ? <Line k="时长" v={durationText(created.durationSnapshot)} /> : null}
            {left === null ? (
              <Line k="支付剩余" v="已超时（将被自动取消）" />
            ) : (
              <Line k="支付剩余" v={left} />
            )}
          </dl>

          <p className="mt-4 rounded-2xl bg-ivory px-3.5 py-3 text-[11px] leading-relaxed text-navy/75 ring-1 ring-gold/25">
            订单已生成并锁定资源。支付入口在「订单」页（本轮下一个交付），未支付前该预约会保留到超时自动取消。
          </p>

          <div className="mt-4 flex gap-2">
            <button
              type="button"
              onClick={onAgain}
              className="h-11 flex-1 rounded-2xl bg-warmgray text-sm font-semibold text-navy/80"
            >
              再约一单
            </button>
            <button
              type="button"
              onClick={onHome}
              className="h-11 flex-1 rounded-2xl bg-btn-gradient text-sm font-semibold text-white shadow-btn"
            >
              回主页
            </button>
          </div>
        </section>
      </div>
    </main>
  )
}

function Line({ k, v, strong, mono }: { k: string; v: string; strong?: boolean; mono?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <dt className="shrink-0 text-muted">{k}</dt>
      <dd className={cx('min-w-0 truncate text-right', strong && 'font-mono text-sm font-bold text-navy', mono && 'font-mono text-[11px]')}>
        {v}
      </dd>
    </div>
  )
}

function Section({ title, delay, children }: { title: string; delay: number; children: ReactNode }) {
  return (
    <section className="animate-fade-up mt-4" style={{ animationDelay: `${delay}ms` }}>
      <h2 className="mb-2 px-1 text-sm font-bold text-ink">{title}</h2>
      {children}
    </section>
  )
}

function Hint({ children }: { children: ReactNode }) {
  return <p className="mt-2 rounded-2xl bg-warmgray px-3 py-2.5 text-[11px] leading-relaxed text-muted">{children}</p>
}
