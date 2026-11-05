import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useNavigate } from 'react-router-dom'
import Card from '../components/ui/Card'
import Layout from '../components/layout/Layout'
import { useToast } from '../components/ui/Toast'
import { storeContext } from '../auth/storeContext'
import { BizError } from '../api/types'
import {
  dashboardApi,
  fenToYuanText,
  type AppointmentItem,
  type BoardingStayItem,
  type OverviewView,
  type PageData,
  type ScheduleItem,
} from '../api/dashboard'
import {
  IconClipboard,
  IconPaw,
  IconUsers,
  IconWallet,
} from '../components/ui/icons'
import {
  notificationApi,
  notificationLink,
  type NotificationItem,
} from '../api/notification'
import { cx } from '../lib/cx'
import { timeAgo } from '../lib/dateText'
import { TONE } from '../lib/tone'
import { BANNER_TAGS, QUICK_ACTIONS, TRUST_BADGES } from '../mock/dashboard'

/**
 * B 端工作台首页（对齐 B-HomePage.png）。数据区块已接真实接口（ADR-013）：
 * 概览 /store/stats/overview · 待办 /store/appointments · 在店 /store/boarding-stays · 排班 /store/schedules
 * · 最新消息 /store/notifications（ADR-047，这张卡曾是 ADR-013 记下的诚实占位）。
 * 每区块独立 加载/空/错误+重试，单块失败不影响他块；切店 storeId 变化即全量重载并丢弃陈旧响应。
 * 「今日」不在前端计算——不传 date，由后端按 Asia/Shanghai 业务时区判定。
 */
export default function HomePage() {
  const { push } = useToast()
  const navigate = useNavigate()
  const ctx = useSyncExternalStore(storeContext.subscribe, storeContext.get)
  const storeId = ctx.storeId

  /** 卡片右上角「查看全部」与快捷动作的统一入口：有落地页就跳，没有就如实说未开工 */
  const go = (label: string, to?: string) => () => {
    if (to) navigate(to)
    else push(`${label}：模块将随后端段次上线，敬请期待`, 'info')
  }

  return (
    <Layout active="home">
      <div className="grid gap-5 p-4 sm:p-6 2xl:grid-cols-[minmax(0,1fr)_320px]">
        <div className="flex min-w-0 flex-col gap-5">
          <Banner name={ctx.identity?.name ?? null} />
          <StatsGrid storeId={storeId} />
          <div className="grid gap-5 xl:grid-cols-2">
            <TodoCard storeId={storeId} onGo={go} />
            <PetCard storeId={storeId} onGo={go} />
          </div>
          <div className="grid gap-5 xl:grid-cols-2">
            <PromoCard onGo={go} />
            <TrustCard />
          </div>
        </div>
        <div className="flex min-w-0 flex-col gap-5">
          <QuickActionsCard onGo={go} />
          <LatestMessagesCard storeId={storeId} onGo={go} />
          <ShiftCard storeId={storeId} onGo={go} />
        </div>
      </div>
    </Layout>
  )
}

/* ---- 区块数据 hook：loading / ready / error + reload；seq 守卫丢弃陈旧响应（含切店后的旧回包） ---- */

type Block<T> = { state: 'loading' } | { state: 'error'; message: string } | { state: 'ready'; data: T }

function useBlock<T>(fetcher: () => Promise<T>, reloadKey: unknown): { block: Block<T>; reload: () => void } {
  const [block, setBlock] = useState<Block<T>>({ state: 'loading' })
  const [tick, setTick] = useState(0)
  const seq = useRef(0)
  const fetcherRef = useRef(fetcher)
  fetcherRef.current = fetcher

  useEffect(() => {
    if (reloadKey == null) return
    const my = ++seq.current
    setBlock({ state: 'loading' })
    fetcherRef.current().then(
      (data) => {
        if (my === seq.current) setBlock({ state: 'ready', data })
      },
      (err: unknown) => {
        if (my !== seq.current) return
        setBlock({ state: 'error', message: err instanceof BizError ? err.message : '加载失败，请重试' })
      },
    )
  }, [reloadKey, tick])

  return { block, reload: () => setTick((t) => t + 1) }
}

function BlockError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center gap-3 py-8 text-center">
      <p className="text-sm text-[#E8506B]">{message}</p>
      <button
        type="button"
        onClick={onRetry}
        className="h-8 rounded-lg border border-line px-4 text-xs font-semibold text-ink transition hover:border-blue hover:text-blue"
      >
        重试
      </button>
    </div>
  )
}

function BlockEmpty({ text }: { text: string }) {
  return <p className="py-8 text-center text-sm text-muted">{text}</p>
}

function BlockLoading() {
  return (
    <div className="flex items-center justify-center gap-2 py-8" role="status" aria-label="加载中">
      <span className="h-5 w-5 animate-spin rounded-full border-2 border-blue border-t-transparent" />
      <span className="text-xs text-muted">加载中…</span>
    </div>
  )
}

/* ---- 横幅 ---- */

function Banner({ name }: { name: string | null }) {
  return (
    <section className="relative overflow-hidden rounded-2xl bg-gradient-to-r from-blue-soft via-blue-mist to-blue-mist shadow-soft">
      <div className="relative z-10 flex flex-col justify-center px-6 py-9 sm:px-8 sm:py-11 md:pr-[48%]">
        <h1 className="text-[26px] font-black text-ink sm:text-[30px]">{name ? `你好，${name}！` : '你好！'}</h1>
        <p className="mt-2 text-sm text-muted">用专业和爱心，守护每一位小可爱</p>
        <div className="mt-5 flex flex-wrap gap-3">
          {BANNER_TAGS.map(({ label, Icon }) => (
            <span key={label} className="flex items-center gap-1.5 rounded-full bg-white px-4 py-1.5 text-xs font-semibold text-ink shadow-soft">
              <Icon className="h-3.5 w-3.5 text-blue" />
              {label}
            </span>
          ))}
        </div>
      </div>
      <img
        src="/images/banner-pets.png"
        alt="金毛犬与橘猫在客厅"
        className="absolute inset-y-0 right-0 hidden h-full w-[44%] object-cover md:block"
      />
    </section>
  )
}

/* ---- 经营概览（单请求驱动四卡；金额 BigInt 分→元） ---- */

function StatsGrid({ storeId }: { storeId: string | null }) {
  const { block, reload } = useBlock<OverviewView>(() => dashboardApi.overview(), storeId)
  if (block.state === 'loading') {
    return (
      <section className="grid gap-5 sm:grid-cols-2 2xl:grid-cols-4" aria-label="经营概览">
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="rounded-2xl bg-white p-5 shadow-soft ring-1 ring-line">
            <BlockLoading />
          </div>
        ))}
      </section>
    )
  }
  if (block.state === 'error') {
    return (
      <section className="rounded-2xl bg-white shadow-soft ring-1 ring-line" aria-label="经营概览">
        <BlockError message={`经营概览加载失败：${block.message}`} onRetry={reload} />
      </section>
    )
  }
  const o = block.data!
  const rev = o.revenue
  const deltaNeg = BigInt(rev.deltaAmount) < 0n
  const deltaUp = !deltaNeg && BigInt(rev.deltaAmount) > 0n
  const revSub =
    rev.deltaPercent == null
      ? `较昨日 ${fenToYuanText(rev.deltaAmount)}（昨日无基数）`
      : `较昨日 ${deltaUp ? '↑' : deltaNeg ? '↓' : ''}${Math.abs(rev.deltaPercent)}%`
  const cards: { key: string; label: string; value: string; unit: string; sub: string; tone: keyof typeof TONE; Icon: typeof IconPaw }[] = [
    { key: 'pets', label: '在店宠物', value: o.inStorePetCount, unit: '只', sub: '寄养在住（入住/每日护理）', tone: 'blue', Icon: IconPaw },
    { key: 'orders', label: '今日订单', value: o.todayOrderCount, unit: '单', sub: '今日创建 · 不含已取消', tone: 'green', Icon: IconClipboard },
    { key: 'staff', label: '在岗员工', value: o.onDutyStaffCount, unit: '人', sub: '今日 WORKING 班次', tone: 'purple', Icon: IconUsers },
    { key: 'income', label: '今日收入', value: fenToYuanText(rev.todayAmount), unit: '', sub: `实收−退款 · ${revSub}`, tone: 'orange', Icon: IconWallet },
  ]
  return (
    <section className="grid gap-5 sm:grid-cols-2 2xl:grid-cols-4" aria-label="经营概览">
      {cards.map(({ key, label, value, unit, sub, tone, Icon }) => (
        <div key={key} className="rounded-2xl bg-white p-5 shadow-soft ring-1 ring-line">
          <div className="flex items-center gap-2.5">
            <span className="grid h-9 w-9 place-items-center rounded-full" style={{ background: TONE[tone].bg }}>
              <Icon className="h-5 w-5" style={{ color: TONE[tone].fg }} />
            </span>
            <p className="text-sm text-muted">{label}</p>
          </div>
          <p className="mt-3 text-[28px] font-black leading-none text-ink">
            {value}
            {unit && <span className="ml-1.5 text-sm font-semibold text-muted">{unit}</span>}
          </p>
          <p className="mt-2 truncate text-xs text-muted">{sub}</p>
        </div>
      ))}
    </section>
  )
}

/* ---- 今日待办（真实预约列表；状态映射后端枚举） ---- */

const APT_STATUS: Record<string, { label: string; tone: keyof typeof TONE }> = {
  PENDING_PAYMENT: { label: '待支付', tone: 'gray' },
  CONFIRMED: { label: '已确认', tone: 'blue' },
  // ADR-043：客户提了取消申请、等门店审批退款——台位还占着，必须显眼（橙色=有决定要做）
  CANCEL_REQUESTED: { label: '取消审核中', tone: 'orange' },
  WAIT_SERVICE: { label: '待服务', tone: 'orange' },
  SERVING: { label: '服务中', tone: 'green' },
  COMPLETED: { label: '已完成', tone: 'gray' },
  CANCELLED: { label: '已取消', tone: 'gray' },
}

function TodoCard({ storeId, onGo }: { storeId: string | null; onGo: (label: string, to?: string) => () => void }) {
  const { block, reload } = useBlock<PageData<AppointmentItem>>(() => dashboardApi.todayAppointments(5), storeId)
  return (
    // 预约没有独立列表页；订单与预约 1:1（uk_appt），「查看全部」落到可按日期区间筛的订单管理页
    <Card title="今日待办" onAction={onGo('今日待办', '/orders')}>
      {block.state === 'loading' && <BlockLoading />}
      {block.state === 'error' && <BlockError message={block.message ?? '加载失败'} onRetry={reload} />}
      {block.state === 'ready' && block.data.list.length === 0 && <BlockEmpty text="今日暂无预约" />}
      {block.state === 'ready' && block.data.list.length > 0 && (
        <>
          <ul className="divide-y divide-line">
            {block.data.list.map((t) => {
              const st = APT_STATUS[t.status] ?? { label: t.status, tone: 'gray' as const }
              return (
                <li key={t.id} className="flex items-center gap-3 py-2.5">
                  <span className="w-12 shrink-0 text-xs font-semibold text-muted">{t.startTime ?? '全天'}</span>
                  <span className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-blue-soft text-blue">
                    <IconPaw className="h-5 w-5" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-bold text-ink">{t.pet?.name ?? t.appointmentNo}</p>
                    <p className="truncate text-xs text-muted">
                      {t.serviceName}
                      {t.employee?.name ? ` · ${t.employee.name}` : ''}
                    </p>
                  </div>
                  <span className="shrink-0 rounded-full px-2.5 py-1 text-xs font-semibold" style={{ background: TONE[st.tone].bg, color: TONE[st.tone].fg }}>
                    {st.label}
                  </span>
                </li>
              )
            })}
          </ul>
          {BigInt(block.data.total) > block.data.list.length && (
            <p className="pt-2 text-center text-[11px] text-muted">今日共 {block.data.total} 条，仅展示前 {block.data.list.length} 条</p>
          )}
        </>
      )}
    </Card>
  )
}

/* ---- 在店宠物（托管在住单） ---- */

const STAY_STATUS: Record<string, { label: string; tone: keyof typeof TONE }> = {
  CHECKED_IN: { label: '已入住', tone: 'green' },
  DAILY_CARE: { label: '每日护理', tone: 'purple' },
}

function PetCard({ storeId, onGo }: { storeId: string | null; onGo: (label: string, to?: string) => () => void }) {
  const { block, reload } = useBlock<BoardingStayItem[]>(() => dashboardApi.inStorePets(), storeId)
  return (
    <Card title="在店宠物" onAction={onGo('在店宠物', '/boarding')}>
      {block.state === 'loading' && <BlockLoading />}
      {block.state === 'error' && <BlockError message={block.message ?? '加载失败'} onRetry={reload} />}
      {block.state === 'ready' && block.data.length === 0 && <BlockEmpty text="当前没有在店宠物" />}
      {block.state === 'ready' && block.data.length > 0 && (
        <ul className="divide-y divide-line">
          {block.data.map((s) => {
            const st = STAY_STATUS[s.status] ?? { label: s.status, tone: 'blue' as const }
            const petName = s.pet?.name ?? '未关联宠物'
            const gender = s.pet?.gender === 'MALE' ? '♂' : s.pet?.gender === 'FEMALE' ? '♀' : ''
            return (
              <li key={s.id} className="flex items-center gap-3 py-2.5">
                <span className="grid h-11 w-11 shrink-0 place-items-center rounded-full bg-blue-soft text-blue">
                  <IconPaw className="h-5 w-5" />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-bold text-ink">{petName}</p>
                  <p className="truncate text-xs text-muted">
                    {gender && <span className={gender === '♂' ? 'text-blue' : 'text-[#E8506B]'}>{gender} </span>}
                    {s.pet?.breed ?? '—'} · 至 {s.checkoutDate}
                  </p>
                </div>
                <span className="shrink-0 rounded-full px-2.5 py-1 text-xs font-semibold" style={{ background: TONE[st.tone].bg, color: TONE[st.tone].fg }}>
                  {st.label}
                </span>
                <button
                  type="button"
                  onClick={onGo('宠物详情', '/pets')}
                  className="shrink-0 rounded-lg border border-line px-3 py-1 text-xs text-ink transition hover:border-blue hover:text-blue"
                >
                  详情
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </Card>
  )
}

/* ---- 今日排班 ---- */

const ROLE_LABEL: Record<string, string> = {
  STORE_MANAGER: '店长',
  DOCTOR: '医生',
  GROOMER: '美容师',
  DOG_WALKER: '遛狗师',
  CARETAKER: '护理员',
  RECEPTIONIST: '前台',
  ADMIN: '管理员',
}
const ROLE_TONES = ['green', 'blue', 'purple', 'orange', 'pink'] as const

function ShiftCard({ storeId, onGo }: { storeId: string | null; onGo: (label: string, to?: string) => () => void }) {
  const { block, reload } = useBlock<ScheduleItem[]>(() => dashboardApi.todaySchedules(), storeId)
  return (
    <Card title="今日排班" onAction={onGo('排班管理', '/employees')}>
      {block.state === 'loading' && <BlockLoading />}
      {block.state === 'error' && <BlockError message={block.message ?? '加载失败'} onRetry={reload} />}
      {block.state === 'ready' && block.data.length === 0 && <BlockEmpty text="今日暂无排班" />}
      {block.state === 'ready' && block.data.length > 0 && (
        <ul className="flex flex-col gap-3">
          {block.data.map((s, i) => (
            <li key={s.id} className="flex items-center gap-2.5 text-sm">
              <i className="h-2 w-2 shrink-0 rounded-full" style={{ background: TONE[ROLE_TONES[i % ROLE_TONES.length]].fg }} />
              <span className="w-16 shrink-0 font-bold text-ink">{ROLE_LABEL[s.roleCodes[0] ?? ''] ?? s.roleCodes[0] ?? '员工'}</span>
              <span className="min-w-0 flex-1 truncate text-muted">{s.employeeName ?? '—'}</span>
              {s.status === 'LEAVE' ? (
                <span className="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold" style={{ background: TONE.gray.bg, color: TONE.gray.fg }}>
                  请假
                </span>
              ) : (
                <span className="shrink-0 text-xs text-muted">
                  {s.startTime} - {s.endTime}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

/**
 * 最新消息（ADR-047）：门店收件箱的前 5 条。
 * 这张卡当年是 ADR-013 记下的诚实占位（"门店维度消息 API 尚未存在，ntf 表无 store 维度"）——
 * 现在读侧有了（GET /store/notifications，按收件人+门店两个过滤），所以这里直连真数据，仍不放 mock。
 * 只展示标题与时间：卡片不是消息中心，正文与已读操作在 /messages；
 * 认不出跳转目标的行（如迁移前的 legacy 消息）点了就回消息中心，不去猜路由。
 */
function LatestMessagesCard({ storeId, onGo }: { storeId: string | null; onGo: (label: string, to?: string) => () => void }) {
  const { block, reload } = useBlock<NotificationItem[]>(
    async () => (await notificationApi.list(1, 5)).list ?? [],
    storeId,
  )
  return (
    <Card title="最新消息" onAction={onGo('消息中心', '/messages')}>
      {block.state === 'loading' && <BlockLoading />}
      {block.state === 'error' && <BlockError message={block.message ?? '加载失败'} onRetry={reload} />}
      {block.state === 'ready' && block.data.length === 0 && <BlockEmpty text="本店暂无新消息" />}
      {block.state === 'ready' && block.data.length > 0 && (
        <ul className="flex flex-col gap-2.5">
          {block.data.map((item) => (
            <li key={item.id}>
              <button
                type="button"
                onClick={onGo(item.title, notificationLink(item) ?? '/messages')}
                className="flex w-full items-center gap-2 text-left"
              >
                <i
                  aria-hidden="true"
                  className={cx('h-2 w-2 shrink-0 rounded-full', item.read ? 'bg-line' : 'bg-[#E8506B]')}
                />
                <span className={cx('min-w-0 flex-1 truncate text-sm', item.read ? 'text-muted' : 'font-semibold text-ink')}>
                  {item.title}
                </span>
                <span className="shrink-0 text-[11px] text-muted/70">{timeAgo(item.createdAt)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

/* ---- 静态区块 ---- */

/** 快捷动作的落地页；「安排遛狗」落到遛狗看板（ADR-049）：门店代客下单未拍板，调度入口先给到全店队列 */
const ACTION_ROUTES: Record<string, string | undefined> = {
  'new-boarding': '/boarding',
  'new-bathing': '/bathing',
  'new-walking': '/walking',
  'view-orders': '/orders',
}

function QuickActionsCard({ onGo }: { onGo: (label: string, to?: string) => () => void }) {
  return (
    <section className="rounded-2xl bg-white p-5 shadow-soft ring-1 ring-line">
      <h2 className="mb-4 text-base font-bold text-ink">快速操作</h2>
      <div className="grid grid-cols-3 gap-y-5">
        {QUICK_ACTIONS.map(({ key, label, Icon, tone }) => (
          <button key={key} type="button" onClick={onGo(label, ACTION_ROUTES[key])} className="flex flex-col items-center gap-2">
            <span className="grid h-12 w-12 place-items-center rounded-full transition hover:scale-105" style={{ background: TONE[tone].bg }}>
              <Icon className="h-6 w-6" style={{ color: TONE[tone].fg }} />
            </span>
            <span className="text-xs text-ink">{label}</span>
          </button>
        ))}
      </div>
    </section>
  )
}

function PromoCard({ onGo }: { onGo: (label: string, to?: string) => () => void }) {
  return (
    <section className="flex items-center gap-5 rounded-2xl bg-white p-4 shadow-soft ring-1 ring-line">
      <img src="/images/promo-corgi.png" alt="草地上奔跑的柯基" className="h-28 w-40 shrink-0 rounded-xl object-cover sm:w-44" />
      <div className="min-w-0">
        <p className="text-base font-black text-ink">用心服务 让宠物更快乐</p>
        <p className="mt-1.5 text-xs leading-relaxed text-muted">专业的宠物托管、遛狗、洗澡、照顾服务</p>
        <button
          type="button"
          onClick={onGo('服务介绍')}
          className="mt-3 rounded-lg bg-blue-soft px-4 py-2 text-xs font-bold text-blue transition hover:bg-blue hover:text-white"
        >
          了解更多 →
        </button>
      </div>
    </section>
  )
}

function TrustCard() {
  return (
    <section className="grid h-full grid-cols-2 items-center gap-y-5 rounded-2xl bg-white p-5 shadow-soft ring-1 ring-line sm:grid-cols-4">
      {TRUST_BADGES.map(({ label, Icon, tone }) => (
        <div key={label} className="flex flex-col items-center gap-2 text-center">
          <span className="grid h-10 w-10 place-items-center rounded-full" style={{ background: TONE[tone].bg }}>
            <Icon className="h-5 w-5" style={{ color: TONE[tone].fg }} />
          </span>
          <span className="text-xs font-semibold text-ink">{label}</span>
        </div>
      ))}
    </section>
  )
}
