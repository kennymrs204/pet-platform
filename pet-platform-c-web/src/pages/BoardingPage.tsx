import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { CustomerStayView, DEMO_PET_LABEL, STAY_TABS, StayStatus, stayApi, stayDayNo, stayStatusTone, STAY_STATUS_TEXT } from '../api/boarding'
import { PetVO, petApi } from '../api/pet'
import { tokenStore } from '../auth/tokenStore'
import { useBizFail } from '../auth/useBizFail'
import BottomNav from '../components/layout/BottomNav'
import PageHeader from '../components/layout/PageHeader'
import PetAvatar from '../components/pet/PetAvatar'
import { IconChevronRight, IconHouse } from '../components/ui/icons'
import { cx } from '../lib/cx'
import { dateText } from '../lib/dateText'
import { SPECIES_LABEL } from '../lib/petText'

/**
 * C 端「我的托管」列表。
 * 数据源：GET /customer/boarding-stays（ADR-021 已实现，status 逗号多值、后端按 checkin_date DESC 排序）。
 * 仅当容器未含该构建时 stayApi 才回退演示数据并置 demo=true，页面据此黄条明示。
 * 宠物名称由 /customer/pets 本地 join（契约只给 petId）。
 */
export default function BoardingPage() {
  const navigate = useNavigate()
  const fail = useBizFail()
  const userId = tokenStore.getUserId()

  const [tab, setTab] = useState(STAY_TABS[0].key)
  const [stays, setStays] = useState<CustomerStayView[] | null>(null)
  const [demo, setDemo] = useState(false)
  const [pets, setPets] = useState<PetVO[]>([])

  useEffect(() => {
    if (!userId) navigate('/login', { replace: true })
  }, [userId, navigate])

  useEffect(() => {
    const ac = new AbortController()
    petApi
      .list()
      .then((d) => {
        if (!ac.signal.aborted) setPets(Array.isArray(d) ? d : [])
      })
      .catch(() => {
        /* 宠物拉取失败只影响名称显示，不拦托管列表 */
      })
    return () => ac.abort()
  }, [])

  const load = useCallback(
    (statuses: StayStatus[]) => {
      setStays(null)
      stayApi
        .list(statuses)
        .then((r) => {
          setStays(r.list)
          setDemo(r.demo)
        })
        .catch(fail)
    },
    [fail],
  )

  useEffect(() => {
    load((STAY_TABS.find((t) => t.key === tab) ?? STAY_TABS[0]).statuses)
  }, [tab, load])

  const petById = useMemo(() => new Map(pets.map((p) => [String(p.id), p])), [pets])

  return (
    <main className="min-h-[100dvh] bg-brand-gradient">
      <div className="mx-auto max-w-md px-4 pb-32">
        <PageHeader title="我的托管" fallback="/home" right={{ label: '去预约', onClick: () => navigate('/booking') }} />

        <div className="flex gap-2 overflow-x-auto pb-1">
          {STAY_TABS.map((t) => {
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
                {on && stays ? <span className="ml-1 font-normal opacity-80">{stays.length}</span> : null}
              </button>
            )
          })}
        </div>

        {demo && stays && stays.length > 0 && (
          <p className="mt-3 rounded-2xl border border-gold/40 bg-gold/10 px-3 py-2 text-[11px] leading-relaxed text-navy">
            当前后端构建未含住单列表端点，以下为本地演示数据；日报字段结构与 ADR-015 §4/§5 的真实契约一致。
          </p>
        )}

        {stays === null ? (
          <div className="mt-3 space-y-3" aria-busy="true">
            <SkeletonStay />
            <SkeletonStay />
          </div>
        ) : stays.length === 0 ? (
          <div className="mt-6 rounded-3xl bg-white/85 p-8 text-center shadow-soft ring-1 ring-navy/5">
            <IconHouse className="mx-auto h-8 w-8 text-gold-dark/50" />
            <p className="mt-3 text-sm font-semibold text-ink">这个页签下没有托管单</p>
            <p className="mt-1 text-xs text-muted">
              下单「宠物托管」并支付后会自动生成入住单；支付完成即可在这里跟踪每日照护。
            </p>
            <button
              type="button"
              onClick={() => navigate('/booking')}
              className="mt-4 inline-flex h-10 items-center rounded-2xl bg-btn-gradient px-5 text-sm font-semibold text-white shadow-btn"
            >
              去约托管服务
            </button>
          </div>
        ) : (
          <ul className="mt-3 space-y-3">
            {stays.map((s, i) => (
              <StayRow
                key={s.id}
                stay={s}
                delay={i * 60}
                pet={petById.get(String(s.petId)) ?? null}
                onOpen={() => navigate(`/boarding/${s.id}`)}
              />
            ))}
          </ul>
        )}
      </div>
      <BottomNav active="mine" />
    </main>
  )
}

function StayRow({
  stay,
  pet,
  delay,
  onOpen,
}: {
  stay: CustomerStayView
  delay: number
  pet: PetVO | null
  onOpen: () => void
}) {
  const label = pet ? pet.name : (DEMO_PET_LABEL[stay.petId] ?? '毛孩子')
  return (
    <li className="animate-fade-up rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5" style={{ animationDelay: `${delay}ms` }}>
      <div className="flex items-start gap-3">
        {pet ? (
          <PetAvatar pet={pet} className="h-12 w-12" />
        ) : (
          <span className="grid h-12 w-12 shrink-0 place-items-center rounded-2xl bg-warmgray text-[10px] text-muted ring-1 ring-navy/5">
            演示
          </span>
        )}
        <div className="min-w-0 flex-1">
          <p className="flex items-center gap-2">
            <span className="truncate text-sm font-bold text-ink">{label}</span>
            <span className={cx('shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold', stayStatusTone(stay.status))}>
              {STAY_STATUS_TEXT[stay.status]}
            </span>
            <span className="ml-auto shrink-0 text-[11px] font-semibold text-navy">{stayDayNo(stay)}</span>
          </p>
          <p className="mt-1 truncate text-[11px] text-muted">
            {pet ? `${SPECIES_LABEL[pet.species]}${pet.breed ? ` · ${pet.breed}` : ''} · ` : ''}
            {dateText(stay.checkinDate)} 至 {dateText(stay.checkoutDate)}
          </p>
          <p className="mt-0.5 text-[11px] text-muted">
            {stay.actualCheckinAt
              ? `实际入住 ${stay.actualCheckinAt.slice(5, 16).replace('T', ' ')}`
              : '尚未办理入住'}
            {stay.caretakerName ? ` · 护理员 ${stay.caretakerName}` : ''}
          </p>
        </div>
      </div>

      {stay.specialInstructions && (
        <p className="mt-2.5 line-clamp-2 rounded-2xl bg-ivory px-3 py-2 text-[11px] leading-relaxed text-navy/75 ring-1 ring-gold/25">
          {stay.specialInstructions}
        </p>
      )}

      <button
        type="button"
        onClick={onOpen}
        className="mt-3 flex h-10 w-full items-center justify-center gap-0.5 rounded-2xl bg-btn-gradient text-xs font-semibold text-white shadow-btn active:scale-[0.99]"
      >
        查看托管日报 <IconChevronRight className="h-3.5 w-3.5" />
      </button>
    </li>
  )
}

function SkeletonStay() {
  return (
    <div className="animate-pulse rounded-3xl bg-white/70 p-4 ring-1 ring-navy/5" aria-hidden="true">
      <div className="flex gap-3">
        <div className="h-12 w-12 shrink-0 rounded-2xl bg-warmgray" />
        <div className="flex-1">
          <div className="h-4 w-2/5 rounded bg-warmgray" />
          <div className="mt-2 h-3 w-3/5 rounded bg-warmgray" />
          <div className="mt-2 h-3 w-1/2 rounded bg-warmgray" />
        </div>
      </div>
      <div className="mt-3 h-10 rounded-2xl bg-warmgray" />
    </div>
  )
}
