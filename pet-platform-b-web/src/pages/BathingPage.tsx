import { useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react'
import Card from '../components/ui/Card'
import Layout from '../components/layout/Layout'
import {
  IconBath,
  IconBulb,
  IconCheckCircle,
  IconChevronDown,
  IconClock,
  IconPaw,
  IconShield,
  IconUser,
  IconUsers,
} from '../components/ui/icons'
import { useToast } from '../components/ui/Toast'
import { storeContext } from '../auth/storeContext'
import { BizError } from '../api/types'
import { fenToYuanText } from '../api/dashboard'
import { ROLE_LABEL, employeesApi, type EmployeeRow } from '../api/employees'
import {
  BATH_QUEUE_STATUSES,
  BATH_QUEUE_STATUS_LABEL,
  SERVICE_ITEM_OPTIONS,
  SERVICE_TYPE_LABEL,
  STATION_STATUS_LABEL,
  VISIBLE_SLOT_END,
  VISIBLE_SLOT_START,
  bathingApi,
  buildStaffLoad,
  dateOfOffset,
  dateTimeText,
  durationText,
  filterTasks,
  resourceTypeLabel,
  slotOccupied,
  slotToTime,
  tabCounts,
  taskTimeText,
  timeToSlot,
  typeCounts,
  type BathQueueStatus,
  type BathServiceType,
  type BathStation,
  type BathingTask,
  type StaffLoad,
  type TaskResult,
} from '../api/bathing'
import { mediaSrc } from '../lib/mediaSrc'
import { isFutureDate } from '../lib/dateText'
import { cx } from '../lib/cx'
import { TONE } from '../lib/tone'
import type { Tone } from '../mock/dashboard'

type Tab = 'ALL' | BathQueueStatus
type View = 'STORE' | 'MINE'

/**
 * B 端洗澡服务 = 洗护（洗澡 / 美容）履约看板，数据全是真实接口（ADR-038）：
 * `GET /store/grooming/queue` + `GET /store/grooming/stations` + 本店名册 `GET /store/employees`。
 * 这一页曾是 B 端唯一整块 mock 的页面，mock 的理由（队列要的服务类型 / 台位 / 履约记录三类字段没有端点给）
 * 已随 ADR-038 消失，所以**不留演示回退**：拉不到就报错，不放假数据。
 *
 * 视图口径由服务端决定：持 `appointment:view:store`（店长/前台）拿全店，只有 `task:accept` 的角色
 * 被服务端强制收窄成本人任务（出参 `storeWideScope=false`）。页面上的「店看板 / 我的任务」只是在
 * 这份已收窄的结果上再隐一层，前端不自己判权限。
 *
 * 写侧已落地五条：**到店签到**（ADR-042，闸 `appointment:checkin`）与**指派/改派、换台位、开始、完成＋履约记录**
 * （ADR-048，完成时同事务写 `ful_bath_record`；换台位=§8 挂账核销，占用行迁移零列写）。
 * 仍未接入的是门店侧取消（见底部边界卡的原因）。
 */
export default function BathingPage() {
  const { push } = useToast()
  const notReady = (label: string) => () => push(`${label}：后端未实现该动作（门店侧取消牵退款政策链 ADR-023），暂未接入`, 'info')
  const ctx = useSyncExternalStore(storeContext.subscribe, storeContext.get)
  const storeId = ctx.storeId

  const [view, setView] = useState<View>('STORE')
  const [tab, setTab] = useState<Tab>('ALL')
  const [typeFilter, setTypeFilter] = useState<'ALL' | BathServiceType>('ALL')
  const [date, setDate] = useState(dateOfOffset(0))
  const [openId, setOpenId] = useState<string | null>(null)
  // 同一时刻只允许一次签到在飞：双击/连点会打两发 POST，虽然后端幂等但回执会闪两条
  const [checkingId, setCheckingId] = useState<string | null>(null)
  // 指派/开始/完成共用一个"在飞"闸，理由同上（后端是条件更新，多打一发只会多一条回执）
  const [writingId, setWritingId] = useState<string | null>(null)

  const mineOnly = view === 'MINE'
  // 当天两类四态一次取全：页签与类型的计数在这份真数据上现算，切筛选不再发第二次请求
  const queue = useAsync(() => bathingApi.queue({ date }), `${storeId}|${date}`)
  // 台位占用条反映当天全部排期，不随页签/类型筛选变化——否则切到「服务中」会把别的台位画成空的
  const stations = useAsync(() => bathingApi.stations(date), `${storeId}|${date}`)
  // 名册要 emp:view（店长、前台有；美容师、医生、遛狗师没有）→ 10003 走「无权限」空态而不是红错卡
  const roster = useAsync(() => employeesApi.list(), storeId)

  const day = queue.block.state === 'ready' ? queue.block.data : null
  const list = day?.list ?? []
  const status = tab === 'ALL' ? undefined : tab
  const type = typeFilter === 'ALL' ? undefined : typeFilter
  const shown = filterTasks(list, { status, type, mineOnly })
  const counts = day ? tabCounts(filterTasks(list, { type, mineOnly })) : null
  const types = day ? typeCounts(filterTasks(list, { mineOnly })) : null
  const staffRows = roster.block.state === 'ready' && day ? buildStaffLoad(roster.block.data, list) : null
  const rosterRows = roster.block.state === 'ready' ? roster.block.data : []
  const stationRows = stations.block.state === 'ready' ? stations.block.data : []

  const reset = () => setOpenId(null)

  /** 签到后必须回队列拉真相：状态由后端条件更新决定，前端不就地把自己"改成"待服务。 */
  const doCheckin = async (t: BathingTask) => {
    if (checkingId) return
    setCheckingId(t.appointmentId)
    const who = t.pet?.name ?? t.appointmentNo
    try {
      const r = await bathingApi.checkin(t.appointmentId)
      push(r.changed ? `${who} 已签到 → 待服务` : `${who} 此前已签到（本次未改变状态）`, 'success')
      setOpenId(null)
      queue.reload()
    } catch (err) {
      push(err instanceof BizError ? err.message : '签到失败，请稍后重试', 'error')
    } finally {
      setCheckingId(null)
    }
  }

  /**
   * 履约三动作（ADR-048）共用一条纪律：**回执说了什么就显示什么，然后回队列拉真相**。
   * `changed=false` 是幂等重放（同事已点过 / 自己双击），按成功回灰字提示而不是红色错误；
   * 状态推进由后端条件更新裁决，前端绝不就地把自己"改成"服务中/已完成。
   */
  const runTask = async (t: BathingTask, label: string, call: () => Promise<TaskResult>) => {
    if (writingId) return
    setWritingId(t.appointmentId)
    const who = t.pet?.name ?? t.appointmentNo
    try {
      const r = await call()
      push(r.changed ? `${who}：${label} → ${r.status}` : `${who}：该操作此前已完成（本次未改变状态）`, 'success')
      setOpenId(null)
      queue.reload()
    } catch (err) {
      push(err instanceof BizError ? err.message : `${label}失败，请稍后重试`, 'error')
    } finally {
      setWritingId(null)
    }
  }
  const doAssign = (t: BathingTask, employeeId: string) =>
    runTask(t, '已指派', () => bathingApi.assignEmployee(t.appointmentId, employeeId))
  const doChangeResource = (t: BathingTask, resourceId: string) =>
    runTask(t, '已换台位', () => bathingApi.changeResource(t.appointmentId, resourceId))
  const doStart = (t: BathingTask) => runTask(t, '开始服务', () => bathingApi.startService(t.appointmentId))
  const doFinish = (t: BathingTask, serviceItems: string[], remark: string) =>
    runTask(t, '完成服务', () =>
      bathingApi.finishService(t.appointmentId, { serviceItems, remark: remark.trim() || undefined }))

  return (
    <Layout active="bathing">
      <div className="grid gap-5 p-4 sm:p-6 2xl:grid-cols-[minmax(0,1fr)_320px]">
        <div className="flex min-w-0 flex-col gap-5">
          <div className="rounded-2xl bg-white p-5 shadow-soft ring-1 ring-line sm:p-6">
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div>
                <h1 className="text-[26px] font-black text-ink">洗澡服务</h1>
                <p className="mt-1.5 text-sm text-muted">洗护（洗澡 / 美容）履约看板：签到、指派、开始与完成记录</p>
                <p className="mt-1 text-[11px] text-muted">
                  数据源 <code className="font-mono">GET /store/grooming/queue</code>（真实）·
                  {day ? (day.storeWideScope ? ' 口径：全店当日队列（appointment:view:store）' : ' 口径：仅指派给本人的任务（服务端按 task:accept 收窄）') : ' 口径：加载中'}
                </p>
              </div>
              <div className="flex rounded-full bg-blue-mist p-1" role="tablist" aria-label="视图切换">
                {(['STORE', 'MINE'] as View[]).map((v) => (
                  <button
                    key={v}
                    type="button"
                    role="tab"
                    aria-selected={view === v}
                    onClick={() => {
                      setView(v)
                      reset()
                    }}
                    className={cx('h-8 rounded-full px-4 text-xs font-bold transition', view === v ? 'bg-white text-blue shadow-soft' : 'text-muted')}
                  >
                    {v === 'STORE' ? '店看板' : '我的任务'}
                  </button>
                ))}
              </div>
            </div>

            {day && !day.storeWideScope && view === 'STORE' && (
              <p className="mt-4 flex items-start gap-2 rounded-xl bg-blue-mist/70 px-4 py-3 text-xs leading-relaxed text-muted ring-1 ring-line">
                <IconShield className="mt-0.5 h-4 w-4 shrink-0 text-blue" />
                <span>
                  当前账号只有 <code className="font-mono">task:accept</code>（无 <code className="font-mono">appointment:view:store</code>）：
                  「店看板」与「我的任务」是同一份服务端已收窄的结果，看不到别人的单属权限口径，不是加载失败。
                </span>
              </p>
            )}

            {day?.truncated && (
              <p className="mt-4 flex items-start gap-2 rounded-xl bg-[#FFF8E7] px-4 py-3 text-xs leading-relaxed text-[#8A6116] ring-1 ring-[#F3E0B5]">
                <IconBulb className="mt-0.5 h-4 w-4 shrink-0" />
                <span>当天门店预约超过后端 200 条上限，本队列与下面的计数都可能不完整。</span>
              </p>
            )}

            <div className="mt-5 flex flex-wrap items-center gap-2.5">
              <div className="flex flex-wrap gap-2.5" role="tablist" aria-label="队列状态">
                {(['ALL', ...BATH_QUEUE_STATUSES] as Tab[]).map((key) => (
                  <button
                    key={key}
                    type="button"
                    role="tab"
                    aria-selected={tab === key}
                    onClick={() => {
                      setTab(key)
                      reset()
                    }}
                    className={cx(
                      'h-9 rounded-full px-4 text-sm font-semibold transition',
                      tab === key ? 'bg-blue text-white shadow-soft' : 'bg-white text-ink ring-1 ring-line hover:text-blue',
                    )}
                  >
                    {key === 'ALL' ? '全部' : BATH_QUEUE_STATUS_LABEL[key]}
                    {counts && ` (${counts[key]})`}
                  </button>
                ))}
              </div>
              <label className="ml-auto flex items-center gap-2 text-xs text-muted">
                日期
                <input
                  type="date"
                  value={date}
                  onChange={(e) => {
                    setDate(e.target.value || dateOfOffset(0))
                    reset()
                  }}
                  className="h-9 rounded-lg bg-white px-2.5 text-sm text-ink ring-1 ring-line focus:outline-none focus:ring-blue"
                />
              </label>
            </div>

            <div className="mt-3 flex flex-wrap items-center gap-2.5">
              {(['ALL', 'BATH', 'GROOMING'] as const).map((t) => (
                <button
                  key={t}
                  type="button"
                  aria-pressed={typeFilter === t}
                  onClick={() => {
                    setTypeFilter(t)
                    reset()
                  }}
                  className={cx(
                    'flex h-8 items-center gap-1.5 rounded-full px-3.5 text-xs font-semibold transition',
                    typeFilter === t ? 'bg-[#1E3A5F] text-white' : 'bg-white text-ink ring-1 ring-line hover:text-blue',
                  )}
                >
                  {t === 'ALL' ? '洗澡+美容' : SERVICE_TYPE_LABEL[t]}
                  {types && t !== 'ALL' && ` (${types[t]})`}
                </button>
              ))}
              <span className="ml-auto text-[11px] text-muted">
                类型判据 = <code className="font-mono">cat_service.code</code>（PET_BATH / PET_GROOMING），不是服务名快照
              </span>
            </div>

            <div className="mt-5 flex flex-col gap-3">
              {queue.block.state === 'loading' && <ListLoading />}
              {queue.block.state === 'error' && <ListError message={queue.block.message} onRetry={queue.reload} />}
              {day && shown.length === 0 && (
                <p className="rounded-2xl bg-blue-mist/60 py-12 text-center text-sm text-muted">
                  {day.date} 没有符合条件的洗护任务
                  {list.length > 0
                    ? '（当天有别的洗护单，是当前筛选把它们滤掉了）'
                    : mineOnly
                      ? '（切回「店看板」或换日期试试）'
                      : '（换日期试试；本店当天若只有托管或遛狗预约，那些不在本看板）'}
                </p>
              )}
              {shown.map((t) => (
                <TaskBlock
                  key={t.appointmentId}
                  task={t}
                  roster={rosterRows}
                  stations={stationRows}
                  open={openId === t.appointmentId}
                  onToggle={() => setOpenId((v) => (v === t.appointmentId ? null : t.appointmentId))}
                  onNotReady={notReady}
                  onCheckin={doCheckin}
                  onAssign={doAssign}
                  onChangeResource={doChangeResource}
                  onStart={doStart}
                  onFinish={doFinish}
                  checking={checkingId === t.appointmentId}
                  busy={checkingId !== null}
                  canAssign={day?.canAssign === true}
                />
              ))}
            </div>
          </div>

          <section>
            <h2 className="mb-2.5 flex items-center gap-2 text-lg font-black text-ink">
              <IconBath className="h-5 w-5 text-blue" /> 台位今日占用
            </h2>
            <BlockShell block={stations.block} onRetry={stations.reload}>
              {(data) => <StationGrid stations={data} />}
            </BlockShell>
          </section>
        </div>

        <div className="flex min-w-0 flex-col gap-5">
          <TodayCard counts={counts} types={types} truncated={day?.truncated === true} loading={queue.block.state === 'loading'} />
          <StaffCard
            rows={staffRows}
            state={roster.block.state === 'ready' ? (day ? 'ready' : 'loading') : roster.block.state}
            storeWide={day?.storeWideScope ?? null}
            onRetry={() => {
              roster.reload()
              queue.reload()
            }}
          />
          <BoundaryCard />
        </div>
      </div>
    </Layout>
  )
}

/* ---- 区块数据（与首页/宠物/客户/员工页同模式） ---- */

type Block<T> = { state: 'loading' } | { state: 'denied' } | { state: 'error'; message: string } | { state: 'ready'; data: T }

function useAsync<T>(fetcher: () => Promise<T>, reloadKey: unknown): { block: Block<T>; reload: () => void } {
  const [block, setBlock] = useState<Block<T>>({ state: 'loading' })
  const [tick, setTick] = useState(0)
  const seq = useRef(0)
  const fetcherRef = useRef(fetcher)
  fetcherRef.current = fetcher

  useEffect(() => {
    if (reloadKey === null || reloadKey === undefined) return
    const my = ++seq.current
    setBlock({ state: 'loading' })
    fetcherRef.current().then(
      (data) => {
        if (my === seq.current) setBlock({ state: 'ready', data })
      },
      (err: unknown) => {
        if (my !== seq.current) return
        // 10003 是「这个视图不给看」，属权限口径不是故障：渲染成中性空态，别用红错卡吓人
        if (err instanceof BizError && err.code === 10003) setBlock({ state: 'denied' })
        else setBlock({ state: 'error', message: err instanceof BizError ? err.message : '加载失败，请重试' })
      },
    )
  }, [reloadKey, tick])

  return { block, reload: () => setTick((t) => t + 1) }
}

function BlockShell<T>({ block, onRetry, children }: { block: Block<T>; onRetry: () => void; children: (data: T) => ReactNode }) {
  if (block.state === 'loading') {
    return (
      <div className="flex h-24 items-center justify-center rounded-2xl bg-white text-xs text-muted shadow-soft ring-1 ring-line">
        <Spinner /> 加载中…
      </div>
    )
  }
  if (block.state === 'denied') {
    return <p className="rounded-2xl bg-white px-4 py-6 text-center text-xs text-muted shadow-soft ring-1 ring-line">当前账号没有查看该视图的权限</p>
  }
  if (block.state === 'error') {
    return (
      <div className="flex flex-col items-center gap-2 rounded-2xl bg-white px-4 py-6 shadow-soft ring-1 ring-line">
        <p className="text-xs text-[#E8506B]">{block.message}</p>
        <button type="button" onClick={onRetry} className="h-7 rounded-lg border border-line px-3 text-[11px] font-semibold text-ink hover:border-blue hover:text-blue">
          重试
        </button>
      </div>
    )
  }
  return <>{children(block.data)}</>
}

/* ---- 队列行 ---- */

function TaskBlock({
  task: t,
  roster,
  stations,
  open,
  onToggle,
  onNotReady,
  onCheckin,
  onAssign,
  onChangeResource,
  onStart,
  onFinish,
  checking,
  busy,
  canAssign: callerCanAssign,
}: {
  task: BathingTask
  roster: EmployeeRow[]
  stations: BathStation[]
  open: boolean
  onToggle: () => void
  onNotReady: (label: string) => () => void
  onCheckin: (task: BathingTask) => void
  onAssign: (task: BathingTask, employeeId: string) => void
  onChangeResource: (task: BathingTask, resourceId: string) => void
  onStart: (task: BathingTask) => void
  onFinish: (task: BathingTask, serviceItems: string[], remark: string) => void
  checking: boolean
  busy: boolean
  canAssign: boolean
}) {
  const pet = t.pet
  const url = mediaSrc(pet?.avatarUrl)
  // 同 CustomersPage：presigned GET 指向的对象可能不存在（签得出地址 ≠ 图在桶里），加载失败必须回落占位
  const [imgFailed, setImgFailed] = useState(false)
  const avatar = url && !imgFailed ? url : null
  return (
    <div className="overflow-hidden rounded-2xl bg-blue-mist/60 ring-1 ring-line">
      <button type="button" aria-expanded={open} onClick={onToggle} className="flex w-full items-center gap-4 p-4 text-left transition hover:bg-white/60">
        <span className="grid h-12 w-12 shrink-0 place-items-center overflow-hidden rounded-xl bg-white text-blue ring-1 ring-line">
          {avatar ? <img src={avatar} alt={pet?.name ?? '宠物头像'} className="h-full w-full object-cover" onError={() => setImgFailed(true)} /> : <IconPaw className="h-6 w-6" />}
        </span>
        <div className="min-w-0 flex-1">
          <p className="flex flex-wrap items-center gap-2 text-base font-black text-ink">
            <span className="truncate">{pet?.name ?? '宠物档案已删除'}</span>
            <span className="rounded-full bg-white px-2 py-0.5 text-[11px] font-semibold text-muted ring-1 ring-line">
              {pet ? `${speciesText(pet.species)} · ${pet.breed ?? '品种未登记'}` : '无宠物信息'}
            </span>
            <Badge label={t.serviceType ? SERVICE_TYPE_LABEL[t.serviceType] : '类型未知'} tone={t.serviceType === 'BATH' ? 'blue' : 'purple'} />
            <Badge label={BATH_QUEUE_STATUS_LABEL[t.status] ?? t.status} tone={statusTone(t.status)} />
            {isFutureDate(t.serviceDate) && t.status !== 'COMPLETED' && <Badge label="未到日期" tone="orange" />}
            {t.assignedToMe && <Badge label="我的任务" tone="green" />}
          </p>
          <p className="mt-1 truncate text-sm text-muted">
            {t.serviceName ?? '服务名未记录'} · {taskTimeText(t)} · {durationText(t.durationMin)} · {t.price == null ? '价格未记录' : fenToYuanText(t.price)}
          </p>
          <p className="mt-0.5 flex flex-wrap items-center gap-x-3 text-[11px] text-muted">
            <span>执行人：{employeeText(t, roster)}</span>
            <span>需要台位：{resourceTypeLabel(t.requiredResourceType)}</span>
            <span>履约记录：{t.record ? `已提交（${dateTimeText(t.record.finishedAt)}）` : '未提交'}</span>
          </p>
        </div>
        <IconChevronDown className={cx('h-4 w-4 shrink-0 text-muted transition-transform', open && 'rotate-180')} />
      </button>
      {open && (
        <TaskDetail
          task={t}
          roster={roster}
          stations={stations}
          onNotReady={onNotReady}
          onCheckin={onCheckin}
          onAssign={onAssign}
          onChangeResource={onChangeResource}
          onStart={onStart}
          onFinish={onFinish}
          checking={checking}
          busy={busy}
          canAssign={callerCanAssign}
        />
      )}
    </div>
  )
}

function speciesText(species: string | null): string {
  if (species === 'DOG') return '犬'
  if (species === 'CAT') return '猫'
  return species ?? '未知'
}

/** 队列行只给执行人 id + 名字（EmployeeBrief），角色在本店名册里；拿不到名册就只报名字，不编角色。 */
function employeeText(t: BathingTask, roster: EmployeeRow[]): string {
  if (!t.employee) return '未指派'
  const roles = roster.find((e) => e.employeeId === t.employee?.id)?.roleCodes ?? []
  return roles.length ? `${t.employee.name}（${roles.map((c) => ROLE_LABEL[c] ?? c).join('/')}）` : t.employee.name
}

function statusTone(s: BathQueueStatus): Tone {
  if (s === 'COMPLETED') return 'green'
  if (s === 'SERVING') return 'orange'
  if (s === 'WAIT_SERVICE') return 'blue'
  return 'gray'
}

function TaskDetail({
  task: t,
  roster,
  stations,
  onNotReady,
  onCheckin,
  onAssign,
  onChangeResource,
  onStart,
  onFinish,
  checking,
  busy,
  canAssign: callerCanAssign,
}: {
  task: BathingTask
  roster: EmployeeRow[]
  stations: BathStation[]
  onNotReady: (label: string) => () => void
  onCheckin: (task: BathingTask) => void
  onAssign: (task: BathingTask, employeeId: string) => void
  onChangeResource: (task: BathingTask, resourceId: string) => void
  onStart: (task: BathingTask) => void
  onFinish: (task: BathingTask, serviceItems: string[], remark: string) => void
  checking: boolean
  busy: boolean
  canAssign: boolean
}) {
  const [items, setItems] = useState<string[]>(t.record?.serviceItems ?? [])
  const [remark, setRemark] = useState(t.record?.remark ?? '')
  // 指派候选：在职 + 今日有班。技能/时段冲突不在这儿筛——那是后端的判定，前端筛了只会两处口径打架
  const [pick, setPick] = useState('')
  const [pickStation, setPickStation] = useState('')
  const assignable = roster.filter((e) => e.status !== 'RESIGNED' && e.todayShift?.status === 'WORKING')
  // ADR-054：指派是调度职能（店长/前台）——服务端 canAssign 告知，员工看到的是说明而非假控件
  const canAssign = callerCanAssign && (t.status === 'CONFIRMED' || t.status === 'WAIT_SERVICE')
  // 换台位候选：本单要求类型 + AVAILABLE（stations 端点本就只回 AVAILABLE）。
  // 时段重叠的台位禁点（按台位占用条预判）；时间未定档不预判，交给后端 40004 让门店重挑
  const taskSlots = (() => {
    const from = timeToSlot(t.startTime)
    const to = timeToSlot(t.endTime)
    if (from == null || to == null || to <= from) return []
    return Array.from({ length: to - from }, (_, i) => from + i)
  })()
  const stationCandidates = stations.filter((s) => s.type === t.requiredResourceType)
  const stationFreeAt = (s: BathStation) => taskSlots.length === 0 || !taskSlots.some((slot) => slotOccupied(s, slot))
  const toggleItem = (code: string) => setItems((cur) => (cur.includes(code) ? cur.filter((c) => c !== code) : [...cur, code]))
  const pet = t.pet
  const rec = t.record

  return (
    <div className="flex flex-col gap-4 border-t border-line bg-white p-4">
      <div className="grid gap-x-6 gap-y-2.5 sm:grid-cols-2 xl:grid-cols-3">
        <Field label="预约号" value={t.appointmentNo} mono />
        <Field label="服务日期" value={t.serviceDate} />
        <Field label="时段" value={taskTimeText(t)} />
        <Field label="体型" value={pet?.sizeCategory ?? '未登记'} />
        <Field label="性别" value={pet?.gender === 'MALE' ? '公' : pet?.gender === 'FEMALE' ? '母' : '未登记'} />
        <Field label="下单来源" value={t.source === 'RECEPTIONIST' ? '前台代录' : t.source === 'CUSTOMER' ? '顾客自约' : '未记录'} />
        <Field label="要求资源" value={resourceTypeLabel(t.requiredResourceType)} />
        <Field label="实际开始" value={rec ? dateTimeText(rec.startedAt) : '未开始'} />
        <Field label="实际完成" value={rec ? dateTimeText(rec.finishedAt) : '未完成'} />
      </div>

      {t.status === 'COMPLETED' && !rec && (
        <p className="flex items-start gap-2 rounded-xl bg-[#FFF8E7] px-4 py-3 text-xs leading-relaxed text-[#8A6116] ring-1 ring-[#F3E0B5]">
          <IconBulb className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            状态已是「已完成」，但 <code className="font-mono">ful_bath_record</code> 里没有对应记录——该单完成于履约链上线前
            （或记录被软删），此处如实显示「未提交」，不补一条假记录。
          </span>
        </p>
      )}

      {rec && (
        <section className="rounded-xl bg-blue-mist/60 p-3 ring-1 ring-line">
          <p className="mb-2 flex items-center gap-1.5 text-xs font-bold text-muted">
            <IconCheckCircle className="h-3.5 w-3.5 text-[#2FA96B]" /> 履约记录
            <span className="font-normal">· {roster.find((e) => e.employeeId === rec.employeeId)?.name ?? (rec.employeeId ? `员工 #${rec.employeeId}` : '执行人未记录')}</span>
          </p>
          {rec.serviceItems?.length ? (
            <div className="flex flex-wrap gap-1.5">
              {rec.serviceItems.map((c) => (
                <span key={c} className="rounded-md bg-white px-2 py-0.5 text-[11px] font-semibold text-ink ring-1 ring-line">
                  {SERVICE_ITEM_OPTIONS.find((o) => o.code === c)?.label ?? c}
                </span>
              ))}
            </div>
          ) : (
            <p className="text-xs text-muted">执行项目为空（记录里的 JSON 不是字符串数组时，后端按空处理而不是猜内容）</p>
          )}
          <p className="mt-2 text-xs text-muted">{rec.remark || '无备注'}</p>
          <p className="mt-1 text-[11px] text-muted">照片 {rec.photoCount ?? 0} 张：出参刻意只给张数，不给对象 key 或签名地址。</p>
        </section>
      )}

      {(t.status === 'SERVING' || t.status === 'WAIT_SERVICE') && (
        <section>
          <p className="mb-2 flex items-center gap-1.5 text-xs font-bold text-muted">
            <IconBath className="h-3.5 w-3.5 text-blue" /> {t.status === 'SERVING' ? '完成服务并填写记录' : '服务开始前确认'}
          </p>
          <div className="flex flex-wrap gap-2">
            {SERVICE_ITEM_OPTIONS.map((o) => {
              const on = items.includes(o.code)
              return (
                <button
                  key={o.code}
                  type="button"
                  aria-pressed={on}
                  onClick={() => toggleItem(o.code)}
                  className={cx(
                    'h-8 rounded-full px-3.5 text-xs font-semibold transition',
                    on ? 'bg-blue text-white' : 'bg-white text-ink ring-1 ring-line hover:text-blue',
                  )}
                >
                  {o.label}
                </button>
              )
            })}
          </div>
          <textarea
            value={remark}
            onChange={(e) => setRemark(e.target.value)}
            rows={2}
            maxLength={200}
            placeholder="备注（可选，≤200 字）：皮肤状况、配合度、需告知主人的事项"
            className="mt-3 w-full rounded-xl bg-white px-3 py-2 text-sm text-ink ring-1 ring-line placeholder:text-muted focus:outline-none focus:ring-blue"
          />
          <p className="mt-2 text-[11px] leading-relaxed text-muted">
            字段取自 <code className="font-mono">ful_bath_record</code>：实际执行项目 / 起止时间 / 备注 / 照片。
            表内<b>没有</b>水温、沐浴用品、异常标记等列，故此处不提供这些输入项。
          </p>
        </section>
      )}

      {canAssign ? (
        <section className="rounded-xl bg-warmgray/60 p-3">
          <p className="mb-2 flex items-center gap-1.5 text-xs font-bold text-muted">
            <IconUser className="h-3.5 w-3.5 text-blue" /> {t.employee ? '改派执行人' : '指派执行人'}
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <select
              aria-label="选择服务人员"
              value={pick}
              onChange={(e) => setPick(e.target.value)}
              className="h-9 min-w-[12rem] rounded-xl bg-white px-2 text-sm text-ink ring-1 ring-line focus:outline-none focus:ring-blue"
            >
              <option value="">
                {assignable.length ? '选择本店今日有班的员工' : '本店今日无人排班：先到【员工排班】排班，再来指派'}
              </option>
              {assignable
                .filter((e) => e.employeeId !== t.employee?.id)
                .map((e) => (
                  <option key={e.employeeId} value={e.employeeId}>
                    {e.name}（{(e.roleCodes ?? []).map((c) => ROLE_LABEL[c] ?? c).join('/') || '无角色'}）
                  </option>
                ))}
            </select>
            <ActionButton
              Icon={IconUser}
              label={busy ? '提交中…' : t.employee ? '确认改派' : '确认指派'}
              onClick={() => onAssign(t, pick)}
              disabled={!pick || pick === t.employee?.id}
              busy={busy}
            />
          </div>
          <p className="mt-2 text-[11px] leading-relaxed text-muted">
            后端会校验该员工在本店在职、当日班次覆盖本单时段、技能覆盖该服务、且这段时间没有别的单
            （40001/40002/40003 会原样回显）。已开始服务的单不能再改派；**指派是调度职能，员工不可互派任务**。
          </p>
        </section>
      ) : (
        <section className="rounded-xl bg-warmgray/60 p-3">
          <p className="flex items-center gap-1.5 text-xs font-bold text-muted">
            <IconUser className="h-3.5 w-3.5 text-blue" /> 指派需调度权限
          </p>
          <p className="mt-1.5 text-[11px] leading-relaxed text-muted">
            指派/改派是店长与前台的职能（appointment:assign/reassign）：员工不可互派任务。
            这单的执行安排请联系店长；你自己被指派的单会出现在「我的任务」里。
          </p>
        </section>
      )}

      {canAssign && t.requiredResourceType && (
        <section className="rounded-xl bg-warmgray/60 p-3">
          <p className="mb-2 flex items-center gap-1.5 text-xs font-bold text-muted">
            <IconShield className="h-3.5 w-3.5 text-blue" /> 换台位
          </p>
          {stationCandidates.length === 0 ? (
            <p className="text-[11px] text-muted">
              本店没有类型为「{resourceTypeLabel(t.requiredResourceType)}」的可用台位（台账事实），无法换台位。
            </p>
          ) : (
            <div className="flex flex-wrap items-center gap-2">
              <select
                aria-label="选择目标台位"
                value={pickStation}
                onChange={(e) => setPickStation(e.target.value)}
                className="h-9 min-w-[12rem] rounded-xl bg-white px-2 text-sm text-ink ring-1 ring-line focus:outline-none focus:ring-blue"
              >
                <option value="">选择目标台位</option>
                {stationCandidates.map((s) => (
                  <option key={s.resourceId} value={s.resourceId} disabled={!stationFreeAt(s)}>
                    {s.name}（{resourceTypeLabel(s.type)}）{stationFreeAt(s) ? '' : ` · ${taskTimeText(t)} 已被占用`}
                  </option>
                ))}
              </select>
              <ActionButton
                Icon={IconShield}
                label={busy ? '提交中…' : '确认换台位'}
                onClick={() => onChangeResource(t, pickStation)}
                disabled={!pickStation || busy}
                busy={busy}
              />
            </div>
          )}
          <p className="mt-2 text-[11px] leading-relaxed text-muted">
            只能换<b>未开始</b>的单（已开始 50004）；时段重叠的台位按台位占用条禁点，最终裁决在后端——
            目标台位被其他预约占用会回 40004「请重挑」，后端不自动换第二选择。
            目标台位与当前台位相同则幂等不动。
          </p>
        </section>
      )}

      {isFutureDate(t.serviceDate) && t.status !== 'COMPLETED' && (
        <p className="flex items-center gap-1.5 rounded-lg bg-[#FFF8E7] px-3 py-2 text-[11px] leading-relaxed text-[#8A6116]">
          <IconBulb className="h-3.5 w-3.5 shrink-0" />
          该单预约日期是 {t.serviceDate}，到当天可开始服务
        </p>
      )}

      <div className="flex flex-wrap gap-2.5 border-t border-line pt-3">
        {t.status === 'CONFIRMED' && (
          <ActionButton
            Icon={IconCheckCircle}
            label={checking ? '签到中…' : '到店签到'}
            onClick={() => onCheckin(t)}
            disabled={busy}
            busy={checking}
          />
        )}
        {t.status === 'WAIT_SERVICE' && t.employee && (
          <ActionButton Icon={IconClock} label="开始服务" onClick={() => onStart(t)} disabled={busy} busy={busy} />
        )}
        {t.status === 'WAIT_SERVICE' && !t.employee && (
          <p className="flex items-center gap-1.5 text-[11px] leading-relaxed text-muted">
            <IconClock className="h-3.5 w-3.5 shrink-0" />
            未指派执行人：先在上方指派，才能开始服务（完成记录要挂执行人，无执行人的单开始会被 50003 拒）
          </p>
        )}
        {t.status === 'SERVING' && (
          <ActionButton
            Icon={IconCheckCircle}
            label="完成并提交记录"
            onClick={() => onFinish(t, items, remark)}
            disabled={busy}
            busy={busy}
          />
        )}
        {t.status === 'CONFIRMED' && <ActionButton Icon={IconBulb} label="取消预约" onClick={onNotReady('取消预约')} />}
      </div>
    </div>
  )
}

/* ---- 台位占用 ---- */

function StationGrid({ stations }: { stations: BathStation[] }) {
  const slots = Array.from({ length: VISIBLE_SLOT_END - VISIBLE_SLOT_START + 1 }, (_, i) => VISIBLE_SLOT_START + i)
  if (stations.length === 0) {
    return (
      <p className="rounded-2xl bg-white px-4 py-10 text-center text-sm text-muted shadow-soft ring-1 ring-line">
        本店没有状态为 AVAILABLE 的洗澡台 / 美容台（<code className="font-mono">sch_resource</code> 里该类型无可用行）——这是台账事实，不是加载失败。
        美容类服务在目录里通常不配台位，此时美容台一段本就为空。
      </p>
    )
  }
  return (
    <div className="rounded-2xl bg-white p-5 shadow-soft ring-1 ring-line">
      <div className="flex flex-col gap-4">
        {stations.map((s) => {
          const inWindow = s.occupiedSlots.filter((x) => x >= VISIBLE_SLOT_START && x <= VISIBLE_SLOT_END)
          return (
            <div key={s.resourceId}>
              <div className="mb-1.5 flex flex-wrap items-center gap-2">
                <span className="text-sm font-bold text-ink">{s.name}</span>
                <span className="rounded-md bg-blue-mist/60 px-2 py-0.5 text-[11px] font-semibold text-muted ring-1 ring-line">{resourceTypeLabel(s.type)}</span>
                <span
                  className="rounded-full px-2 py-0.5 text-[11px] font-semibold"
                  style={s.status !== 'AVAILABLE' ? { background: TONE.gray.bg, color: TONE.gray.fg } : { background: TONE.green.bg, color: TONE.green.fg }}
                >
                  {STATION_STATUS_LABEL[s.status] ?? s.status}
                </span>
                <span className="ml-auto text-[11px] text-muted">
                  {s.occupiedSlots.length === 0
                    ? '今日无占用'
                    : `今日占用 ${s.occupiedSlots.length} 段${inWindow.length === 0 ? '（都在展示时段外）' : ` · 展示时段内 ${inWindow.length} 段`}`}
                </span>
              </div>
              <div className="flex gap-[2px]" aria-label={`${s.name} 时段占用`}>
                {slots.map((slot) => {
                  const occupied = slotOccupied(s, slot)
                  return (
                    <span
                      key={slot}
                      title={`${slotToTime(slot)}–${slotToTime(slot + 1)} ${occupied ? '已占用' : '空闲'}`}
                      className={cx('h-5 flex-1 rounded-[3px]', occupied ? 'bg-blue' : 'bg-blue-mist')}
                    />
                  )
                })}
              </div>
            </div>
          )
        })}
      </div>
      <div className="mt-4 flex flex-wrap items-center gap-4 text-[11px] text-muted">
        <span className="flex items-center gap-1.5">
          <i className="h-2.5 w-4 rounded-[3px] bg-blue" />
          已占用
        </span>
        <span className="flex items-center gap-1.5">
          <i className="h-2.5 w-4 rounded-[3px] bg-blue-mist" />
          空闲
        </span>
        <span className="ml-auto">
          每格 30 分钟（{slotToTime(VISIBLE_SLOT_START)}–{slotToTime(VISIBLE_SLOT_END + 1)}）· 占用 = <code className="font-mono">/store/grooming/stations</code> 的
          occupiedSlots（ADR-016 排期占用）。队列行不带「哪一单在哪台」：占用条是台位唯一真相，两处出必漂移。
        </span>
      </div>
    </div>
  )
}

/* ---- 右栏 ---- */

function TodayCard({
  counts,
  types,
  truncated,
  loading,
}: {
  counts: Record<'ALL' | BathQueueStatus, number> | null
  types: Record<BathServiceType, number> | null
  truncated: boolean
  loading: boolean
}) {
  const boxes: [string, number | null][] = [
    ['全部任务', counts?.ALL ?? null],
    ['待签到', counts?.CONFIRMED ?? null],
    ['取消审核中', counts?.CANCEL_REQUESTED ?? null],
    ['待服务', counts?.WAIT_SERVICE ?? null],
    ['服务中', counts?.SERVING ?? null],
    ['已完成', counts?.COMPLETED ?? null],
  ]
  return (
    <Card title="今日洗护统计">
      {loading && !counts ? (
        <p className="py-6 text-center text-xs text-muted">
          <Spinner /> 加载中…
        </p>
      ) : (
        <>
          <ul className="flex flex-col gap-2">
            {boxes.map(([label, value]) => (
              <li key={label} className="flex items-center justify-between gap-2 text-[13px]">
                <span className="text-muted">{label}</span>
                <span className="font-black text-ink">{value ?? '—'}</span>
              </li>
            ))}
          </ul>
          {types && (
            <p className="mt-3 rounded-lg bg-blue-mist/60 px-3 py-2 text-[11px] leading-relaxed text-muted">
              洗澡 {types.BATH} 单 · 美容 {types.GROOMING} 单（两者同表同状态机，仅服务与台位类型不同）
              {truncated && <span className="text-[#8A6116]">；当天预约超过 200 条上限，计数可能不完整</span>}
            </p>
          )}
          <p className="mt-2 text-[11px] leading-relaxed text-muted">计数在当天全量队列上现算，不是「当前已显示条数」。</p>
        </>
      )}
    </Card>
  )
}

function StaffCard({ rows, state, storeWide, onRetry }: { rows: StaffLoad[] | null; state: Block<unknown>['state']; storeWide: boolean | null; onRetry: () => void }) {
  return (
    <Card title="美容师在班与负载">
      {state === 'loading' && (
        <p className="py-6 text-center text-xs text-muted">
          <Spinner /> 加载中…
        </p>
      )}
      {state === 'error' && (
        <div className="flex flex-col items-center gap-2 py-5">
          <p className="text-xs text-[#E8506B]">名册加载失败</p>
          <button type="button" onClick={onRetry} className="h-7 rounded-lg border border-line px-3 text-[11px] font-semibold text-ink hover:border-blue hover:text-blue">
            重试
          </button>
        </div>
      )}
      {state === 'denied' && (
        <p className="py-5 text-[11px] leading-relaxed text-muted">
          本卡需要 <code className="font-mono">emp:view</code>（店长、前台有；美容师、医生、遛狗师没有）→ 对这些角色不显示全店负载，属权限口径。
          你自己的任务在左侧队列里。
        </p>
      )}
      {state === 'ready' && rows && (
        <>
          {rows.length === 0 ? (
            <p className="py-5 text-center text-xs text-muted">
              本店名册里没有未离职员工（<code className="font-mono">/store/employees</code> 返回空）
            </p>
          ) : (
            <ul className="flex flex-col gap-2.5">
              {rows.map((s) => (
                <li key={s.employeeId} className="flex items-center gap-2.5 rounded-xl bg-blue-mist/60 px-3 py-2 ring-1 ring-line">
                  <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-white text-blue">
                    <IconUsers className="h-4 w-4" />
                  </span>
                  <span className="min-w-0 flex-1">
                    <p className="truncate text-[13px] font-bold text-ink">
                      {s.name}
                      {!s.onDuty && s.pending + s.serving > 0 && <span className="ml-1.5 text-[11px] font-semibold text-[#E8506B]">未排班却有待办</span>}
                    </p>
                    <p className="truncate text-[11px] text-muted">
                      {s.onDuty ? '今日在班' : '今日未排班'} · {s.roleCodes.length ? s.roleCodes.map((c) => ROLE_LABEL[c] ?? c).join('/') : '未分配角色'}
                    </p>
                  </span>
                  <span className="shrink-0 text-right text-[11px] text-muted">
                    <span className="block font-black text-ink">{s.pending + s.serving}</span>
                    待办
                  </span>
                </li>
              ))}
            </ul>
          )}
          <p className="mt-3 text-[11px] leading-relaxed text-muted">
            在班 = 当日 <code className="font-mono">sch_schedule</code>（ADR-004，与 emp_employee.status 是两回事），负载 = 当天队列按执行人现算。
            {storeWide === false && ' 当前账号只看得到本人任务，故这里的计数只反映本人视野。'}
          </p>
        </>
      )}
    </Card>
  )
}

function BoundaryCard() {
  return (
    <Card title="本页能力边界">
      <ul className="flex flex-col gap-2.5 text-[13px]">
        <li className="flex items-start gap-2">
          <span className="mt-1 h-1.5 w-1.5 shrink-0 rotate-45 bg-[#2FA96B]" />
          <span className="text-ink">真实数据已接入（ADR-038）：四态队列、洗澡/美容分类、全店与本人双视图、日期筛选、台位占用条、履约记录只读回显、宠物头像签名地址</span>
        </li>
        <li className="flex items-start gap-2">
          <span className="mt-1 h-1.5 w-1.5 shrink-0 rotate-45 bg-[#2FA96B]" />
          <span className="text-ink">
            写侧已接五条：<b>到店签到</b>（ADR-042，CONFIRMED→待服务，闸 <code className="font-mono">appointment:checkin</code> = 店长+前台）
            与<b>指派/改派、换台位、开始、完成＋记录落库</b>（ADR-048，闸 <code className="font-mono">appointment:assign/reassign</code>、
            <code className="font-mono">task:start/task:finish</code>，完成时同事务写 ful_bath_record；
            换台位=未开始的单把占用行挪到目标台位，占用冲突 40004 请重挑）。
            没权限的动作点了拿 10003——那是权限口径，不是故障。
          </span>
        </li>
        {[
          ['取消预约', 'P8 §3.4 原与签到合并成 /status?action=，因 CANCEL 要牵退款政策链（ADR-023）而拆开，动作端点尚未实现'],
          ['到店时间', '库里没有这一列（actual_start_at 语义是"服务实际开始"，属 task:start），所以签到只推状态、不记到店时刻'],
          ['美容类换台位', '美容服务目录里 requiredResourceType 为空=不占台位，没有台位可换；洗澡/美容台位类服务才有「换台位」面板'],
          ['宠物过敏 / 疫苗 / 备注', '需 /store/pets/{id} 详情端点，看板不逐行拉（N+1）'],
          ['照片可视', 'ful_bath_record.photos 在本视图只出张数，不签地址'],
        ].map(([label, why]) => (
          <li key={label} className="flex items-start gap-2">
            <span className="mt-1 h-1.5 w-1.5 shrink-0 rotate-45 bg-[#E8506B]" />
            <span className="min-w-0 flex-1">
              <span className="font-semibold text-ink">{label}</span>
              <span className="block text-[11px] text-muted">{why}</span>
            </span>
          </li>
        ))}
      </ul>
      <p className="mt-3 text-[11px] leading-relaxed text-muted">
        写侧回执 <code className="font-mono">changed=false</code> 按幂等成功提示（不弹红错）；每次动作成功后回队列拉服务端真相，绝不就地改状态。
      </p>
    </Card>
  )
}

/* ---- 通用小件 ---- */

function Badge({ label, tone }: { label: string; tone: Tone }) {
  return (
    <span className="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold" style={{ background: TONE[tone].bg, color: TONE[tone].fg }}>
      {label}
    </span>
  )
}

function Field({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div>
      <p className="text-[11px] text-muted">{label}</p>
      <p className={cx('mt-0.5 text-sm font-semibold text-ink', mono && 'font-mono text-[13px]')}>{value}</p>
    </div>
  )
}

function ActionButton({ Icon, label, onClick, disabled, busy }: { Icon: typeof IconUser; label: string; onClick: () => void; disabled?: boolean; busy?: boolean }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={cx(
        'flex h-9 items-center gap-1.5 rounded-lg bg-white px-3.5 text-xs font-semibold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue',
        disabled && 'cursor-not-allowed opacity-50 hover:text-ink hover:ring-line',
      )}
    >
      {busy ? <Spinner /> : <Icon className="h-4 w-4" />} {label}
    </button>
  )
}

function Spinner() {
  return <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-blue border-t-transparent align-[-2px]" aria-hidden="true" />
}

function ListLoading() {
  return (
    <div className="flex flex-col gap-3" role="status" aria-label="队列加载中">
      {[0, 1, 2].map((i) => (
        <div key={i} className="flex h-[104px] items-center justify-center rounded-2xl bg-blue-mist/60 ring-1 ring-line">
          {i === 0 && (
            <span className="text-xs text-muted">
              <Spinner /> 加载洗护队列…
            </span>
          )}
        </div>
      ))}
    </div>
  )
}

function ListError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-2xl bg-blue-mist/60 py-12 ring-1 ring-line">
      <p className="text-sm text-[#E8506B]">队列加载失败：{message}</p>
      <button type="button" onClick={onRetry} className="h-9 rounded-lg border border-line bg-white px-5 text-xs font-semibold text-ink transition hover:border-blue hover:text-blue">
        重试
      </button>
    </div>
  )
}
