import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { AppointmentSelfView, appointmentApi } from '../api/appointment'
import { authApi } from '../api/auth'
import { CustomerStayView, DEMO_PET_LABEL, stayApi, stayDayNo, STAY_STATUS_TEXT } from '../api/boarding'
import { fenToYuan } from '../api/catalog'
import { PetVO, petApi } from '../api/pet'
import { DEMO_STORES, PublicStoreVO, storeApi } from '../api/store'
import { tokenStore } from '../auth/tokenStore'
import { session } from '../auth/session'
import { resetUnreadCount } from '../lib/unreadCount'
import Logo from '../components/ui/Logo'
import BottomNav from '../components/layout/BottomNav'
import NotificationBell from '../components/layout/NotificationBell'
import PetAvatar from '../components/pet/PetAvatar'
import PetPicker from '../components/pet/PetPicker'
import { useToast } from '../components/ui/Toast'
import { cx } from '../lib/cx'
import { displayName } from '../api/profile'
import { useCustomerProfile } from '../lib/useCustomerProfile'
import { dateText } from '../lib/dateText'
import { APPT_STATUS_TEXT, apptStatusTone, apptWhenText, mergeAppointments } from '../lib/orderBoard'
import { SIZE_LABEL, STATUS_TEXT, petAgeText, petWeightText, statusBadgeTone } from '../lib/petText'
import {
  IconCalendar,
  IconCap,
  IconChevronRight,
  IconClock,
  IconGrid,
  IconHeart,
  IconHouse,
  IconList,
  IconLogOut,
  IconMapPin,
  IconPaw,
  IconScissors,
  IconSearch,
  IconStethoscope,
  IconUser,
} from '../components/ui/icons'

/** 服务分类：key 对齐后端 cat_service.category 枚举（GROOMING/MEDICAL/BOARDING/TRAINING/WALK）。 */
const CATEGORIES = [
  { key: 'ALL', label: '全部', Icon: IconGrid },
  { key: 'GROOMING', label: '宠物洗护', Icon: IconScissors },
  { key: 'MEDICAL', label: '医疗保健', Icon: IconHeart },
  { key: 'BOARDING', label: '寄养托管', Icon: IconHouse },
  { key: 'TRAINING', label: '行为训练', Icon: IconCap },
  { key: 'WALK', label: '上门遛狗', Icon: IconPaw },
] as const

type CatKey = (typeof CATEGORIES)[number]['key']

const CAT_LABEL: Record<string, string> = {
  GROOMING: '洗护',
  MEDICAL: '医疗',
  BOARDING: '寄养',
  TRAINING: '训练',
  WALK: '遛狗',
}

/** 时段问候。昵称可空，回落口径见 api/profile 的 displayName。 */
function greeting(d = new Date()): string {
  const h = d.getHours()
  if (h < 6) return '夜深了'
  if (h < 11) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
}

export default function HomePage() {
  const navigate = useNavigate()
  const { push } = useToast()
  const userId = tokenStore.getUserId()
  const { profile } = useCustomerProfile()

  const [keyword, setKeyword] = useState('')
  const [cat, setCat] = useState<CatKey>('ALL')
  const [stores, setStores] = useState<PublicStoreVO[] | null>(null)
  const [demoMode, setDemoMode] = useState(false)
  const [loading, setLoading] = useState(true)
  const reqSeq = useRef(0)

  const [pets, setPets] = useState<PetVO[] | null>(null)
  const [selPet, setSelPet] = useState<string | null>(null)
  const [appts, setAppts] = useState<AppointmentSelfView[] | null>(null)
  const [stays, setStays] = useState<CustomerStayView[]>([])
  const [staysDemo, setStaysDemo] = useState(false)

  useEffect(() => {
    if (!userId) navigate('/login', { replace: true })
  }, [userId, navigate])

  // 宠物与近期安排：任一失败只降级对应卡片，不影响门店区。
  // `?status=` 后端是单值精确匹配，所以"未完成"的四个状态要并发取再合并去重。
  useEffect(() => {
    const ac = new AbortController()
    petApi
      .list()
      .then((d) => {
        if (ac.signal.aborted) return
        const list = Array.isArray(d) ? d : []
        setPets(list)
        const def = list.find((p) => p.status === 'NORMAL') ?? list[0]
        if (def) setSelPet((cur) => (cur && list.some((p) => p.id === cur) ? cur : def.id))
      })
      .catch(() => {
        if (!ac.signal.aborted) setPets([])
      })

    const ACTIVE = ['PENDING_PAYMENT', 'CONFIRMED', 'WAIT_SERVICE', 'SERVING']
    Promise.all(ACTIVE.map((s) => appointmentApi.list(s, 1, 50).then((p) => p.list ?? [])))
      .then((groups) => {
        if (!ac.signal.aborted) setAppts(mergeAppointments(groups))
      })
      .catch(() => {
        if (!ac.signal.aborted) setAppts([])
      })

    // 在店托管：走 stayApi.list（列表端点未上线时回退演示数据并置 demo）
    stayApi
      .list(['CHECKED_IN', 'DAILY_CARE'])
      .then((r) => {
        if (!ac.signal.aborted) {
          setStays(r.list)
          setStaysDemo(r.demo)
        }
      })
      .catch(() => {
        if (!ac.signal.aborted) setStays([])
      })
    return () => ac.abort()
  }, [userId])

  // 门店公开接口属第二段范围；404/网络失败时回退演示数据并明示，上线后自动切真实数据。
  useEffect(() => {
    const seq = ++reqSeq.current
    const ac = new AbortController()
    setLoading(true)
    const timer = window.setTimeout(
      () => {
        storeApi
          .listPublicStores(keyword.trim() || undefined, ac.signal)
          .then((d) => {
            if (seq !== reqSeq.current) return
            setStores(Array.isArray(d) ? d : [])
            setDemoMode(false)
          })
          .catch(() => {
            if (seq !== reqSeq.current || ac.signal.aborted) return
            setStores(DEMO_STORES)
            setDemoMode(true)
          })
          .finally(() => {
            if (seq === reqSeq.current) setLoading(false)
          })
      },
      keyword.trim() ? 300 : 0,
    )
    return () => {
      window.clearTimeout(timer)
      ac.abort()
    }
  }, [keyword])

  const shown = useMemo(() => {
    const list = stores ?? []
    const kw = keyword.trim().toLowerCase()
    return list
      .map((s) => ({
        ...s,
        services: s.services.filter((sv) => cat === 'ALL' || sv.category === cat),
      }))
      .filter(
        (s) =>
          (cat === 'ALL' || s.services.length > 0) &&
          (!kw || s.name.toLowerCase().includes(kw) || s.address.toLowerCase().includes(kw)),
      )
  }, [stores, cat, keyword])

  const pet = useMemo(() => pets?.find((p) => p.id === selPet) ?? null, [pets, selPet])

  /** 门店名取自本页门店列表（真实 /public/stores，ADR-022；演示回退时同样有效）。 */
  const storeName = (id: string) => stores?.find((s) => String(s.id) === id)?.name ?? `门店 ${id}`

  /** 近期安排：按服务日（托管取入住日）升序取前 3 条，覆盖"待支付→服务中"全周期。 */
  const upcoming = useMemo(() => {
    if (!appts) return null
    const key = (a: AppointmentSelfView) =>
      `${a.boardingCheckinDate ?? a.serviceDate ?? '9999-12-31'}${a.startTime ?? ''}`
    return [...appts].sort((x, y) => key(x).localeCompare(key(y))).slice(0, 3)
  }, [appts])

  const logout = async () => {
    try {
      await authApi.logout()
    } catch {
      // 登出失败也清理本地会话，access 自然过期兜底
    }
    session.bump() // 登出前作废在途 refresh，防其把会话重建回来
    tokenStore.clear()
    resetUnreadCount() // 未读数随会话一起清零：换账号不能带着上一个人的红点
    navigate('/login', { replace: true })
  }

  const soon = (name: string) => push(`「${name}」将随后端模块上线，当前为 UI 预览`, 'info')

  return (
    <main className="min-h-[100dvh] bg-brand-gradient">
      <div className="mx-auto max-w-md px-4 pb-32">
        {/* 顶栏 */}
        <header className="animate-fade-up flex items-center justify-between pt-5">
          <button
            type="button"
            onClick={() => soon('门店定位')}
            className="flex items-center gap-1.5 rounded-full bg-white/80 px-3 py-1.5 text-xs font-semibold text-navy shadow-soft ring-1 ring-navy/5 backdrop-blur"
          >
            <IconMapPin className="h-3.5 w-3.5 text-gold-dark" />
            杭州 · 西湖区
            <IconChevronRight className="h-3.5 w-3.5 text-muted" />
          </button>
          <div className="flex items-center gap-2">
            <NotificationBell />
            <button
              type="button"
              aria-label="我的"
              onClick={() => navigate('/profile')}
              className="grid h-9 w-9 place-items-center rounded-full bg-btn-gradient text-gold-light shadow-soft"
            >
              <IconUser className="h-[18px] w-[18px]" />
            </button>
          </div>
        </header>

        {/* 问候 + 搜索 */}
        <section className="animate-fade-up mt-6" style={{ animationDelay: '60ms' }}>
          <div className="flex items-end justify-between">
            <div>
              <h1 className="text-2xl font-bold text-ink">{greeting()}，{displayName(profile)}</h1>
              <p className="mt-1 text-sm text-muted">今天想让毛孩子享受点什么？</p>
            </div>
            <Logo className="h-10 w-10" />
          </div>
          <label className="mt-4 flex items-center gap-2 rounded-2xl bg-white/90 px-4 py-3 shadow-soft ring-1 ring-navy/5 focus-within:ring-2 focus-within:ring-navy/25">
            <IconSearch className="h-[18px] w-[18px] shrink-0 text-muted" />
            <input
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="搜索门店名称或地址"
              className="w-full bg-transparent text-sm text-ink outline-none placeholder:text-muted/70"
            />
            {keyword && (
              <button type="button" onClick={() => setKeyword('')} className="text-xs text-muted hover:text-navy">
                清除
              </button>
            )}
          </label>
        </section>

        {/* 我的宠物：真实 /customer/pets，选中态供下方快捷入口复用 */}
        <section className="animate-fade-up mt-5" style={{ animationDelay: '120ms' }}>
          <div className="flex items-center justify-between">
            <h2 className="text-base font-bold text-ink">我的宠物</h2>
            <button type="button" onClick={() => navigate('/pets')} className="flex items-center text-xs text-navy/70">
              管理档案 <IconChevronRight className="h-3.5 w-3.5" />
            </button>
          </div>
          {pets === null ? (
            <div className="mt-3 h-28 animate-pulse rounded-3xl bg-white/60" aria-busy="true" />
          ) : pets.length === 0 ? (
            <div className="mt-3 rounded-3xl bg-white/85 p-5 text-center shadow-soft ring-1 ring-navy/5">
              <IconPaw className="mx-auto h-8 w-8 text-gold-dark/50" />
              <p className="mt-2 text-sm font-semibold text-ink">还没有宠物档案</p>
              <p className="mt-1 text-xs text-muted">建档后才能预约服务、查看托管日报</p>
              <button
                type="button"
                onClick={() => navigate('/pets/new')}
                className="mt-3 inline-flex h-10 items-center rounded-2xl bg-btn-gradient px-5 text-sm font-semibold text-white shadow-btn"
              >
                添加宠物
              </button>
            </div>
          ) : (
            <div className="mt-3 rounded-3xl bg-white/85 p-4 shadow-soft ring-1 ring-navy/5">
              <PetPicker pets={pets} value={selPet} onChange={setSelPet} onAdd={() => navigate('/pets/new')} />
              {pet && (
                <div className="mt-3 flex items-center gap-3 border-t border-navy/5 pt-3">
                  <PetAvatar pet={pet} className="h-12 w-12" />
                  <div className="min-w-0 flex-1">
                    <p className="flex items-center gap-2">
                      <span className="truncate text-sm font-bold text-ink">{pet.name}</span>
                      <span className={cx('shrink-0 rounded-full px-2 py-0.5 text-[10px] font-semibold', statusBadgeTone(pet.status))}>
                        {STATUS_TEXT[pet.status]}
                      </span>
                    </p>
                    <p className="mt-0.5 truncate text-[11px] text-muted">
                      {pet.breed || (pet.sizeCategory ? `${SIZE_LABEL[pet.sizeCategory]}宠物` : '未登记品种')} ·{' '}
                      {petAgeText(pet.birthday, pet.birthdayApprox)} · {petWeightText(pet.weightKg)}
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => navigate(`/pets/${pet.id}`)}
                    className="shrink-0 rounded-xl bg-warmgray px-3 py-2 text-[11px] font-semibold text-navy/80"
                  >
                    档案
                  </button>
                </div>
              )}
              {pet?.vaccineSummary && (
                <p className="mt-2.5 flex items-start gap-1.5 text-[11px] leading-relaxed text-muted">
                  <IconHeart className="mt-0.5 h-3.5 w-3.5 shrink-0 text-gold-dark" />
                  {pet.vaccineSummary}
                </p>
              )}
            </div>
          )}
        </section>

        {/* 近期安排：真实 /customer/appointments（未完成态） */}
        <section className="animate-fade-up mt-5" style={{ animationDelay: '180ms' }}>
          <div className="flex items-center justify-between">
            <h2 className="text-base font-bold text-ink">近期安排</h2>
            <button type="button" onClick={() => navigate('/orders')} className="flex items-center text-xs text-navy/70">
              查看全部 <IconChevronRight className="h-3.5 w-3.5" />
            </button>
          </div>
          {upcoming === null ? (
            <div className="mt-3 space-y-2" aria-busy="true">
              <div className="h-16 animate-pulse rounded-2xl bg-white/60" />
              <div className="h-16 animate-pulse rounded-2xl bg-white/60" />
            </div>
          ) : upcoming.length === 0 ? (
            <div className="mt-3 rounded-3xl bg-white/85 p-5 text-center shadow-soft ring-1 ring-navy/5">
              <IconCalendar className="mx-auto h-7 w-7 text-gold-dark/50" />
              <p className="mt-2 text-sm font-semibold text-ink">还没有待处理的预约</p>
              <p className="mt-1 text-xs text-muted">下单后这里会显示进度与待支付提醒</p>
              <button
                type="button"
                onClick={() => navigate('/booking')}
                className="mt-3 inline-flex h-10 items-center rounded-2xl bg-btn-gradient px-5 text-sm font-semibold text-white shadow-btn"
              >
                去约服务
              </button>
            </div>
          ) : (
            <ul className="mt-3 space-y-2">
              {upcoming.map((a) => (
                <li key={a.id}>
                  <button
                    type="button"
                    onClick={() => navigate(`/orders/${a.id}`)}
                    className="flex w-full items-center gap-3 rounded-2xl bg-white/85 p-3.5 text-left shadow-soft ring-1 ring-navy/5 transition hover:bg-white"
                  >
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center gap-2">
                        <span className="truncate text-sm font-bold text-ink">{a.serviceNameSnapshot}</span>
                        <span className={cx('shrink-0 rounded-full px-2 py-0.5 text-[10px] font-semibold', apptStatusTone(a.status))}>
                          {APPT_STATUS_TEXT[a.status] ?? a.status}
                        </span>
                      </span>
                      <span className="mt-1 block truncate text-[11px] text-muted">
                        {apptWhenText(a)} · {storeName(String(a.storeId))}
                      </span>
                    </span>
                    <span className="shrink-0 text-right">
                      <span className="block font-mono text-sm font-bold text-navy">¥{fenToYuan(a.priceSnapshot)}</span>
                      <span className="mt-1 block text-[10px] font-semibold text-navy/70">
                        {a.status === 'PENDING_PAYMENT' ? '去支付 ›' : '查看详情 ›'}
                      </span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>

        {/* 托管进行中：数据来自 stayApi.list（住单列表端点未上线时为演示数据，卡片上标「演示数据」） */}
        {stays.length > 0 && (
          <section className="animate-fade-up mt-5 rounded-3xl bg-white/85 p-4 shadow-soft ring-1 ring-navy/5" style={{ animationDelay: '240ms' }}>
            <div className="flex items-center gap-2">
              <IconHouse className="h-4 w-4 text-gold-dark" />
              <h2 className="text-sm font-bold text-ink">托管进行中</h2>
              {staysDemo && (
                <span className="rounded-full bg-gold/15 px-2 py-0.5 text-[10px] font-semibold text-gold-dark">演示数据</span>
              )}
              <button
                type="button"
                onClick={() => navigate('/boarding')}
                className="ml-auto flex items-center text-[11px] text-navy/70"
              >
                查看全部 <IconChevronRight className="h-3 w-3" />
              </button>
            </div>
            <ul className="mt-3 space-y-2.5">
              {stays.slice(0, 3).map((s) => {
                const p = pets?.find((x) => String(x.id) === String(s.petId)) ?? null
                return (
                  <li key={s.id} className="flex items-center gap-3">
                    {p ? (
                      <PetAvatar pet={p} className="h-10 w-10" />
                    ) : (
                      <span className="grid h-10 w-10 shrink-0 place-items-center rounded-2xl bg-warmgray text-[9px] text-muted ring-1 ring-navy/5">
                        演示
                      </span>
                    )}
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-semibold text-ink">
                        {p ? p.name : (DEMO_PET_LABEL[s.petId] ?? '毛孩子')} · {STAY_STATUS_TEXT[s.status]}
                      </span>
                      <span className="mt-0.5 block truncate text-[11px] text-muted">
                        {stayDayNo(s)} · 计划离店 {dateText(s.checkoutDate)}
                        {s.caretakerName ? ` · 护理员 ${s.caretakerName}` : ''}
                      </span>
                    </span>
                    <button
                      type="button"
                      onClick={() => navigate(`/boarding/${s.id}`)}
                      className="shrink-0 rounded-xl bg-btn-gradient px-3 py-2 text-[11px] font-semibold text-white"
                    >
                      日报
                    </button>
                  </li>
                )
              })}
            </ul>
          </section>
        )}

        {/* 快捷服务：四个入口全部接已交付的真实页面 */}
        <section className="animate-fade-up mt-5 grid grid-cols-2 gap-3" style={{ animationDelay: '300ms' }}>
          <QuickTile Icon={IconCalendar} title="预约服务" hint="选服务、选时段、下单" onClick={() => navigate('/booking')} />
          <QuickTile Icon={IconPaw} title="我的宠物" hint="档案与健康记录" onClick={() => navigate('/pets')} />
          <QuickTile Icon={IconList} title="订单中心" hint="待支付、进度、详情" onClick={() => navigate('/orders')} />
          <QuickTile
            Icon={IconStethoscope}
            title="健康档案"
            hint={pet ? `${pet.name} 的过敏与慢性病` : '先添加宠物档案'}
            onClick={() => navigate(pet ? `/pets/${pet.id}` : '/pets/new')}
          />
        </section>

        {/* 分类 */}
        <nav className="animate-fade-up mt-5 grid grid-cols-5 gap-2" style={{ animationDelay: '360ms' }}>
          {CATEGORIES.map(({ key, label, Icon }) => {
            const active = cat === key
            return (
              <button
                key={key}
                type="button"
                onClick={() => setCat(key)}
                aria-pressed={active}
                className={cx(
                  'flex flex-col items-center gap-1.5 rounded-2xl px-1 py-2.5 text-[11px] font-semibold transition',
                  active
                    ? 'bg-btn-gradient text-white shadow-btn'
                    : 'bg-white/80 text-navy ring-1 ring-navy/5 hover:bg-white',
                )}
              >
                <Icon className={cx('h-5 w-5', active ? 'text-gold-light' : 'text-gold-dark')} />
                {label}
              </button>
            )
          })}
        </nav>

        {/* 演示数据提示 */}
        {demoMode && (
          <p className="mt-4 rounded-2xl border border-gold/40 bg-gold/10 px-3 py-2 text-[11px] leading-relaxed text-navy">
            门店接口（GET /public/stores）本次请求失败，以下为本地兜底演示数据，与 demo 夹具落库内容一致。
          </p>
        )}

        {/* 门店列表 */}
        <section className="mt-5">
          <div className="flex items-center justify-between">
            <h2 className="text-base font-bold text-ink">
              附近门店
              {!loading && <span className="ml-1.5 text-xs font-medium text-muted">{shown.length} 家</span>}
            </h2>
            <button type="button" onClick={() => soon('门店地图')} className="flex items-center text-xs text-navy/70">
              地图模式 <IconChevronRight className="h-3.5 w-3.5" />
            </button>
          </div>

          {loading ? (
            <div className="mt-3 space-y-3" aria-busy="true">
              <SkeletonCard />
              <SkeletonCard />
            </div>
          ) : shown.length === 0 ? (
            <div className="mt-3 rounded-3xl bg-white/85 p-8 text-center shadow-soft ring-1 ring-navy/5">
              <IconPaw className="mx-auto h-8 w-8 text-gold-dark/60" />
              <p className="mt-3 text-sm font-semibold text-ink">该条件下暂无在营门店</p>
              <p className="mt-1 text-xs text-muted">换个分类或关键词试试；上线更多门店后可在这里看到。</p>
            </div>
          ) : (
            <div className="mt-3 space-y-3">
              {shown.map((s, i) => (
                <StoreCard key={s.id} store={s} delay={i * 70} onBook={() => navigate(`/booking?storeId=${s.id}`)} />
              ))}
            </div>
          )}
        </section>

        {/* 会话 */}
        <div className="mt-8 flex items-center justify-between rounded-2xl bg-white/70 px-4 py-3 ring-1 ring-navy/5">
          <span className="text-[11px] text-muted">
            用户 ID <span className="font-mono text-navy/80">{userId}</span>
          </span>
          <button
            type="button"
            onClick={() => void logout()}
            className="flex items-center gap-1 text-xs font-semibold text-navy/80 hover:text-navy"
          >
            <IconLogOut className="h-3.5 w-3.5" /> 退出登录
          </button>
        </div>
      </div>

      {/* 底部导航（全站唯一口径） */}
      <BottomNav active="home" />
    </main>
  )
}

function StoreCard({
  store,
  delay,
  onBook,
}: {
  store: PublicStoreVO
  delay: number
  onBook: () => void
}) {
  const open = store.status === 'OPEN'
  return (
    <article
      className="animate-fade-up rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5"
      style={{ animationDelay: `${delay}ms` }}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="truncate text-[15px] font-bold text-ink">{store.name}</h3>
          <p className="mt-1 flex items-center gap-1 text-xs text-muted">
            <IconMapPin className="h-3 w-3 shrink-0" />
            <span className="truncate">{store.address}</span>
          </p>
        </div>
        <span
          className={cx(
            'shrink-0 rounded-full px-2.5 py-1 text-[11px] font-semibold',
            open ? 'bg-emerald-50 text-emerald-600 ring-1 ring-emerald-200/70' : 'bg-warmgray text-muted ring-1 ring-navy/5',
          )}
        >
          {open ? '营业中' : '休息中'}
        </span>
      </div>

      {store.services.length > 0 ? (
        <ul className="mt-3 divide-y divide-navy/5 border-t border-navy/5">
          {store.services.map((sv) => (
            <li key={sv.serviceId} className="flex items-center justify-between gap-3 py-2.5">
              <div className="min-w-0">
                <p className="flex items-center gap-1.5 text-sm font-semibold text-ink">
                  <span className="truncate">{sv.name}</span>
                  <span className="shrink-0 rounded-md bg-ivory px-1.5 py-0.5 text-[10px] font-medium text-gold-dark ring-1 ring-gold/30">
                    {CAT_LABEL[sv.category] ?? sv.category}
                  </span>
                </p>
                <p className="mt-0.5 flex items-center gap-1 text-[11px] text-muted">
                  <IconClock className="h-3 w-3" /> {sv.durationMin} 分钟
                </p>
              </div>
              <p className="shrink-0 font-mono text-sm font-bold text-navy">¥{Math.round(sv.price / 100)}</p>
            </li>
          ))}
        </ul>
      ) : (
        <p className="mt-3 rounded-2xl bg-warmgray px-3 py-2.5 text-xs text-muted">
          该门店暂未上架服务（价目表 status=ON 为空），换一家门店看看
        </p>
      )}

      <button
        type="button"
        onClick={onBook}
        className="mt-3 h-10 w-full rounded-2xl bg-btn-gradient text-sm font-semibold text-white shadow-btn transition hover:opacity-95 active:scale-[0.99]"
      >
        立即预约
      </button>
    </article>
  )
}

function SkeletonCard() {
  return (
    <div className="animate-pulse rounded-3xl bg-white/70 p-4 ring-1 ring-navy/5" aria-hidden="true">
      <div className="h-4 w-1/3 rounded bg-warmgray" />
      <div className="mt-2 h-3 w-1/2 rounded bg-warmgray" />
      <div className="mt-4 space-y-2.5">
        <div className="h-3 rounded bg-warmgray" />
        <div className="h-3 rounded bg-warmgray" />
      </div>
      <div className="mt-4 h-10 rounded-2xl bg-warmgray" />
    </div>
  )
}

function QuickTile({
  Icon,
  title,
  hint,
  onClick,
}: {
  Icon: (p: { className?: string }) => JSX.Element
  title: string
  hint: string
  onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex items-center gap-3 rounded-3xl bg-white/85 p-4 text-left shadow-soft ring-1 ring-navy/5 transition hover:bg-white"
    >
      <span className="grid h-10 w-10 shrink-0 place-items-center rounded-2xl bg-ivory text-gold-dark ring-1 ring-gold/25">
        <Icon className="h-5 w-5" />
      </span>
      <span className="min-w-0">
        <span className="block truncate text-sm font-bold text-ink">{title}</span>
        <span className="mt-0.5 block truncate text-[11px] text-muted">{hint}</span>
      </span>
    </button>
  )
}

