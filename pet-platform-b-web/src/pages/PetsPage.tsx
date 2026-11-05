import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import Card from '../components/ui/Card'
import Layout from '../components/layout/Layout'
import {
  IconChevronRight,
  IconClipboard,
  IconClock,
  IconEdit,
  IconHelp,
  IconHouse,
  IconMore,
  IconPaw,
  IconPlus,
  IconXCircle,
} from '../components/ui/icons'
import { useToast } from '../components/ui/Toast'
import AddPetModal from '../components/pet/AddPetModal'
import BoardingDailyReportDrawer from '../components/pet/BoardingDailyReportDrawer'
import PetDeleteModal from '../components/pet/PetDeleteModal'
import PetEditDrawer from '../components/pet/PetEditDrawer'
import PetHealthDrawer from '../components/pet/PetHealthDrawer'
import PetServiceHistoryDrawer from '../components/pet/PetServiceHistoryDrawer'
import { storeContext, storeLabel } from '../auth/storeContext'
import { mediaSrc } from '../lib/mediaSrc'
import { BizError } from '../api/types'
import type { PageData } from '../api/dashboard'
import {
  PET_STATUS_LABEL,
  SPECIES_LABEL,
  petsApi,
  petAgeText,
  petWeightText,
  type PetItem,
  type PetOverview,
  type PetProfile,
  type PetSpecies,
} from '../api/pets'
import { canCreatePet, canDeletePet, canEditPet, canManagePetHealth, canViewPetHealth, planAfterPetCreated, planAfterPetUpdated, serviceStatusView } from '../lib/petManage'
import { cx } from '../lib/cx'
import { TONE } from '../lib/tone'
import { FAQS } from '../mock/pets'

type Tab = 'ALL' | PetSpecies

/**
 * 宠物管理页（对齐 pet-TakeCare.png）。列表/概览/托管摘要已接真实只读接口（ADR-014）；
 * 添加（客户 360 同一条创建链）、编辑基础资料、健康档案只读快照、当前门店服务历史
 * 和当前门店托管日报已接真实接口。
 * 页签筛选走 species 参数重新请求（非前端过滤）；切店自动重拉并丢弃陈旧响应。
 */
export default function PetsPage() {
  const { push } = useToast()
  const notReady = (label: string) => () => push(`${label}：功能暂未接入`, 'info')
  const ctx = useSyncExternalStore(storeContext.subscribe, storeContext.get)
  const storeId = ctx.storeId
  const [tab, setTab] = useState<Tab>('ALL')
  const [addOpen, setAddOpen] = useState(false)
  const [editPet, setEditPet] = useState<PetItem | null>(null)
  const [healthPet, setHealthPet] = useState<PetItem | null>(null)
  const [servicePet, setServicePet] = useState<PetItem | null>(null)
  const [deletePet, setDeletePet] = useState<PetItem | null>(null)
  const [dailyReport, setDailyReport] = useState<{ pet: PetItem; stayId: string } | null>(null)

  const currentStore = ctx.candidates.find((s) => s.storeId === storeId) ?? null
  const currentStoreName = currentStore ? storeLabel(currentStore) : storeId ? `门店 ${storeId}` : '当前门店'
  const currentRoles = currentStore?.roles ?? []
  const canAdd = canCreatePet(ctx.platformAdmin, currentRoles)
  const canEdit = canEditPet(ctx.platformAdmin, currentRoles)
  const canDelete = canDeletePet(ctx.platformAdmin, currentRoles)
  const canViewHealth = canViewPetHealth(ctx.platformAdmin, currentRoles)
  const canManageHealth = canManagePetHealth(ctx.platformAdmin, currentRoles)

  const overview = useAsync<PetOverview>(() => petsApi.overview(), storeId)
  const list = useAsync(() => petsApi.list(tab === 'ALL' ? {} : { species: tab, pageSize: 20 }), `${storeId}|${tab}`)

  // 切店后关闭当前门店的弹窗/抽屉，防止旧客户或旧宠物请求串到新上下文。
  useEffect(() => {
    setAddOpen(false)
    setEditPet(null)
    setHealthPet(null)
    setServicePet(null)
    setDeletePet(null)
    setDailyReport(null)
  }, [storeId])

  const onPetCreated = (pet: PetProfile) => {
    setAddOpen(false)
    const plan = planAfterPetCreated(tab, pet.species)
    if (plan.tab !== tab) setTab(plan.tab)
    if (plan.refreshList) list.reload()
    if (plan.refreshOverview) overview.reload()
  }

  const onPetUpdated = (pet: PetProfile) => {
    setEditPet(null)
    const plan = planAfterPetUpdated(tab, pet.species)
    if (plan.tab !== tab) setTab(plan.tab)
    if (plan.refreshList) list.reload()
    if (plan.refreshOverview) overview.reload()
  }

  const counts = overview.block.state === 'ready' ? overview.block.data : null
  const otherCount = counts
    ? String(BigInt(counts.totalPets) - BigInt(counts.dogCount) - BigInt(counts.catCount))
    : null
  const tabs: { key: Tab; label: string; count: string | null }[] = [
    { key: 'ALL', label: '全部宠物', count: counts?.totalPets ?? null },
    { key: 'DOG', label: '犬类', count: counts?.dogCount ?? null },
    { key: 'CAT', label: '猫类', count: counts?.catCount ?? null },
    ...(otherCount && otherCount !== '0' ? [{ key: 'OTHER' as Tab, label: '其他', count: otherCount }] : []),
  ]

  return (
    <Layout active="pets">
      <div className="grid gap-4 p-4 sm:p-6 2xl:grid-cols-[minmax(0,1fr)_300px]">
        <div className="flex min-w-0 flex-col gap-4">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h1 className="text-2xl font-black tracking-tight text-ink">宠物管理</h1>
              <p className="mt-1 text-[13px] text-muted">管理您的宠物信息，查看健康档案与服务记录</p>
            </div>
            <button
              type="button"
              onClick={() => setAddOpen(true)}
              disabled={!canAdd || !storeId}
              title={!canAdd ? '当前账号无新增宠物权限（customer:update）' : undefined}
              className="flex h-9 items-center gap-1.5 rounded-lg bg-blue px-4 text-[13px] font-bold text-white shadow-soft transition hover:bg-blue-deep focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue/40 disabled:cursor-not-allowed disabled:opacity-50"
            >
              <IconPlus className="h-4 w-4" /> 添加宠物
            </button>
          </div>

          <div className="flex flex-wrap items-center gap-1.5" role="tablist" aria-label="宠物种类筛选">
            {tabs.map(({ key, label, count }) => (
              <button
                key={key}
                type="button"
                role="tab"
                aria-selected={tab === key}
                onClick={() => setTab(key)}
                className={cx(
                  'inline-flex h-8 items-center gap-1.5 rounded-lg px-3 text-xs font-semibold transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue/40',
                  tab === key
                    ? 'bg-blue text-white shadow-soft'
                    : 'bg-white text-muted ring-1 ring-line hover:text-blue',
                )}
              >
                {label}
                {count != null && (
                  <span
                    className={cx(
                      'rounded px-1 text-[11px] font-bold leading-4',
                      tab === key ? 'bg-white/20 text-white' : 'bg-blue-mist text-muted',
                    )}
                  >
                    {count}
                  </span>
                )}
              </button>
            ))}
          </div>

          <div className="flex flex-col gap-3">
            {list.block.state === 'loading' && <ListLoading />}
            {list.block.state === 'error' && (
              <ListError message={list.block.message} onRetry={list.reload} />
            )}
            {list.block.state === 'ready' && list.block.data.list.length === 0 && (
              <p className="rounded-xl bg-white py-10 text-center text-sm text-muted ring-1 ring-line">当前门店暂无宠物</p>
            )}
            {list.block.state === 'ready' && list.block.data.list.map((p) => (
              <PetRow
                key={p.id}
                pet={p}
                onNotReady={notReady}
                onEdit={() => setEditPet(p)}
                canEdit={canEdit}
                onHealth={() => setHealthPet(p)}
                onService={() => setServicePet(p)}
                onDelete={() => setDeletePet(p)}
                canDelete={canDelete}
                onDailyReport={(stayId) => setDailyReport({ pet: p, stayId })}
              />
            ))}
            {list.block.state === 'ready' && BigInt(list.block.data.total) > list.block.data.list.length && (
              <p className="text-center text-[11px] text-muted">
                共 {list.block.data.total} 条，当前展示第 {list.block.data.pageNum} 页（{list.block.data.list.length} 条）；分页控件将随写侧迭代接入
              </p>
            )}
          </div>
        </div>

        <div className="flex min-w-0 flex-col gap-4">
          <OverviewCard block={overview.block} onRetry={overview.reload} />
          <BoardingCard block={list.block} onNotReady={notReady} />
          <FaqCard />
        </div>
      </div>

      {addOpen && storeId && (
        <AddPetModal
          storeId={storeId}
          onClose={() => setAddOpen(false)}
          onCreated={onPetCreated}
        />
      )}
      {editPet && storeId && (
        <PetEditDrawer
          pet={editPet}
          storeId={storeId}
          onClose={() => setEditPet(null)}
          onSaved={onPetUpdated}
        />
      )}
      {healthPet && storeId && (
        <PetHealthDrawer
          pet={healthPet}
          storeId={storeId}
          canView={canViewHealth}
          canManage={canManageHealth}
          onClose={() => setHealthPet(null)}
          onChanged={list.reload}
        />
      )}
      {servicePet && storeId && (
        <PetServiceHistoryDrawer
          pet={servicePet}
          storeId={storeId}
          onClose={() => setServicePet(null)}
        />
      )}
      {deletePet && storeId && (
        <PetDeleteModal
          key={deletePet.id}
          pet={deletePet}
          onClose={() => setDeletePet(null)}
          onDeleted={() => {
            setDeletePet(null)
            list.reload()
            overview.reload()
            push('宠物档案已删除', 'success')
          }}
        />
      )}
      {dailyReport && storeId && (
        <BoardingDailyReportDrawer
          pet={dailyReport.pet}
          stayId={dailyReport.stayId}
          storeId={storeId}
          storeName={currentStoreName}
          onClose={() => setDailyReport(null)}
        />
      )}
    </Layout>
  )
}

/* ---- 区块数据（与首页同模式：判别联合 + seq 丢弃陈旧响应；不重复造轮子，逻辑一致） ---- */

type Block<T> = { state: 'loading' } | { state: 'error'; message: string } | { state: 'ready'; data: T }

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
        setBlock({ state: 'error', message: err instanceof BizError ? err.message : '加载失败，请重试' })
      },
    )
  }, [reloadKey, tick])

  return { block, reload: () => setTick((t) => t + 1) }
}

/* ---- 列表行 ---- */

/** 头像：有签名地址就出图；空/加载失败/裸 key 一律回退到统一爪印占位（不引入新图片依赖）。 */
function PetAvatar({ name, src, className, iconClass }: { name: string; src: string | null; className: string; iconClass: string }) {
  const [failed, setFailed] = useState(false)
  useEffect(() => setFailed(false), [src])
  if (src && !failed) {
    return <img src={src} alt={name} onError={() => setFailed(true)} className={cx('shrink-0 rounded-lg object-cover', className)} />
  }
  return (
    <span aria-hidden="true" className={cx('grid shrink-0 place-items-center rounded-lg bg-blue-soft text-blue/70', className)}>
      <IconPaw className={iconClass} />
    </span>
  )
}

function PetRow({
  pet,
  onNotReady,
  onEdit,
  canEdit,
  onHealth,
  onService,
  onDelete,
  canDelete,
  onDailyReport,
}: {
  pet: PetItem
  onNotReady: (label: string) => () => void
  onEdit: () => void
  canEdit: boolean
  onHealth: () => void
  onService: () => void
  onDelete: () => void
  canDelete: boolean
  onDailyReport: (stayId: string) => void
}) {
  const gender = pet.gender === 'MALE' ? '♂' : pet.gender === 'FEMALE' ? '♀' : ''
  const statusTone = pet.status === 'NORMAL' ? ('green' as const) : ('gray' as const)
  const healthHints = pet.health
    ? [
        pet.health.allergyCount > 0 ? `过敏 ${pet.health.allergyCount} 项` : null,
        pet.health.chronicCount > 0 ? `慢性病 ${pet.health.chronicCount} 项` : null,
        pet.health.hasSpecialNotes ? '有特殊照护备注' : null,
      ]
        .filter(Boolean)
        .join(' · ')
    : ''
  return (
    <div className="grid grid-cols-[72px_minmax(0,1fr)] items-start gap-3 rounded-xl bg-white p-3.5 ring-1 ring-line transition hover:ring-blue/30 sm:grid-cols-[80px_minmax(0,1fr)] sm:gap-4 xl:grid-cols-[80px_minmax(0,1fr)_250px] xl:items-center">
      <PetAvatar name={pet.name} src={mediaSrc(pet.avatarUrl)} className="h-[72px] w-[72px] sm:h-20 sm:w-20" iconClass="h-8 w-8" />

      <div className="min-w-0">
        <div className="flex items-start gap-2">
          <div className="min-w-0 flex-1">
            <p className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-1">
              <span className="max-w-[160px] truncate text-[15px] font-bold leading-5 text-ink sm:max-w-[240px]" title={pet.name}>
                {pet.name}
              </span>
              {gender && (
                <span className="text-xs font-semibold text-muted" title={pet.gender === 'MALE' ? '公' : '母'}>
                  {gender}
                </span>
              )}
              <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] font-semibold text-muted">
                {SPECIES_LABEL[pet.species]}
              </span>
              <span
                className="inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-bold"
                style={{ background: TONE[statusTone].bg, color: TONE[statusTone].fg }}
              >
                {PET_STATUS_LABEL[pet.status] ?? pet.status}
              </span>
            </p>
            <p className="mt-1 truncate text-xs text-muted">
              {pet.breed ?? '品种未登记'} · {petAgeText(pet.birthday, pet.birthdayApprox)} · {petWeightText(pet.weightKg)}
            </p>
            {healthHints && <p className="mt-1 truncate text-[11px] text-muted">健康提示：{healthHints}</p>}
          </div>
          <button
            type="button"
            aria-label="更多操作"
            onClick={onNotReady('更多操作')}
            className="grid h-7 w-7 shrink-0 place-items-center rounded-md text-muted transition hover:bg-blue-mist hover:text-ink focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue/40"
          >
            <IconMore className="h-4 w-4" />
          </button>
        </div>

        <div className="mt-1.5 flex flex-wrap items-center gap-0.5">
          <ActionButton Icon={IconClipboard} label="健康档案" onClick={onHealth} />
          <ActionButton Icon={IconClock} label="服务记录" onClick={onService} />
          <ActionButton
            Icon={IconEdit}
            label="编辑资料"
            onClick={onEdit}
            disabled={!canEdit}
            title={!canEdit ? '当前账号无编辑宠物权限（customer:update）' : undefined}
          />
          <ActionButton
            Icon={IconXCircle}
            label="删除宠物"
            onClick={onDelete}
            disabled={!canDelete}
            title={!canDelete ? '当前账号无删除宠物权限（customer:update）' : undefined}
          />
        </div>
      </div>

      <div className="col-span-2 min-w-0 border-t border-line pt-3 xl:col-span-1 xl:border-l xl:border-t-0 xl:pl-4 xl:pt-0">
        {pet.boarding
          ? <BoardingPanel pet={pet} onDailyReport={() => onDailyReport(pet.boarding!.stayId)} />
          : <ServicePanel pet={pet} />}
      </div>
    </div>
  )
}

function ActionButton({
  Icon,
  label,
  onClick,
  disabled,
  title,
}: {
  Icon: typeof IconEdit
  label: string
  onClick: () => void
  disabled?: boolean
  title?: string
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      title={title}
      className="flex h-7 items-center gap-1 rounded-md px-2 text-[11px] font-semibold text-muted transition hover:bg-blue-soft hover:text-blue focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue/40 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-muted"
    >
      <Icon className="h-3.5 w-3.5" /> {label}
    </button>
  )
}

/** 最近服务：SERVICE=履约真实完成，APPOINTMENT=预约快照兜底（状态如实展示，不冒充已完成）。 */
function ServicePanel({ pet }: { pet: PetItem }) {
  const s = pet.lastService
  if (!s) {
    return (
      <div className="flex h-full flex-col justify-center">
        <p className="text-[11px] font-semibold text-muted">最近服务</p>
        <p className="mt-1 text-xs text-muted">暂无服务记录</p>
      </div>
    )
  }
  const label = s.source === 'SERVICE' ? (s.label === 'BATH' ? '洗澡服务' : s.label === 'WALK' ? '遛狗服务' : s.label ?? '服务') : s.label ?? '预约'
  const date = s.source === 'SERVICE' ? s.finishedAt?.slice(0, 10) ?? '' : s.serviceDate ?? ''
  const status = s.source === 'SERVICE' ? { label: '已完成', tone: 'green' as const } : serviceStatusView(s.status)
  return (
    <div className="flex h-full flex-col justify-center">
      <p className="text-[11px] font-semibold text-muted">最近服务</p>
      <div className="mt-1 flex items-center gap-2">
        <p className="min-w-0 flex-1 truncate text-[13px] font-bold text-ink" title={label}>{label}</p>
        <span
          className="shrink-0 rounded px-1.5 py-0.5 text-[10px] font-semibold"
          style={{ background: TONE[status.tone].bg, color: TONE[status.tone].fg }}
        >
          {status.label}
        </span>
      </div>
      <p className="mt-0.5 truncate text-[11px] text-muted">{date || '日期未定'}</p>
    </div>
  )
}

/** 托管面板：只用真实 boarding 字段（入住/计划离店日期）；日报入口复用门店端住单详情/护理记录。 */
function BoardingPanel({ pet, onDailyReport }: { pet: PetItem; onDailyReport: () => void }) {
  const b = pet.boarding
  if (!b) return null
  const checkedIn = b.status === 'CHECKED_IN'
  return (
    <div className="flex h-full flex-col justify-center">
      <div className="flex items-center justify-between gap-2">
        <p className="text-[11px] font-semibold text-muted">托管中</p>
        <span
          className="shrink-0 rounded px-1.5 py-0.5 text-[10px] font-semibold"
          style={checkedIn ? { background: TONE.orange.bg, color: TONE.orange.fg } : { background: TONE.purple.bg, color: TONE.purple.fg }}
        >
          {checkedIn ? '已入住' : '每日护理'}
        </span>
      </div>
      <p className="mt-1 truncate text-[13px] font-bold text-ink">{checkedIn ? '寄养在店' : '每日护理中'}</p>
      <p className="mt-0.5 truncate text-[11px] text-muted">入住 {b.checkinDate} · 计划离店 {b.checkoutDate}</p>
      <button
        type="button"
        onClick={onDailyReport}
        className="mt-1.5 inline-flex items-center gap-0.5 self-start rounded text-[11px] font-bold text-blue transition hover:text-blue-deep focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue/40"
      >
        查看托管日报 <IconChevronRight className="h-3 w-3" />
      </button>
    </div>
  )
}

/* ---- 右栏 ---- */

function OverviewCard({ block, onRetry }: { block: Block<PetOverview>; onRetry: () => void }) {
  const boxes: { key: string; label: string; value: string | null; Icon: typeof IconPaw }[] =
    block.state === 'ready'
      ? [
          { key: 'total', label: '宠物总数', value: block.data.totalPets, Icon: IconPaw },
          { key: 'dog', label: '犬类', value: block.data.dogCount, Icon: IconPaw },
          { key: 'cat', label: '猫类', value: block.data.catCount, Icon: IconPaw },
          { key: 'boarding', label: '托管中', value: block.data.inBoardingCount, Icon: IconHouse },
        ]
      : []
  return (
    <section className="relative overflow-hidden rounded-2xl bg-white p-4 shadow-soft ring-1 ring-line" aria-label="宠物数据概览">
      <IconPawMark />
      <h2 className="text-base font-bold text-ink">宠物数据概览</h2>
      {block.state === 'loading' && (
        <div className="py-6 text-center text-xs text-muted">
          <Spinner /> 加载中…
        </div>
      )}
      {block.state === 'error' && (
        <div className="flex flex-col items-center gap-2 py-6">
          <p className="text-xs text-[#E8506B]">{block.message}</p>
          <button type="button" onClick={onRetry} className="h-7 rounded-lg border border-line px-3 text-[11px] font-semibold text-ink hover:border-blue hover:text-blue">
            重试
          </button>
        </div>
      )}
      {block.state === 'ready' && (
        <div className="mt-4 grid grid-cols-4 gap-2">
          {boxes.map(({ key, label, value, Icon }) => (
            <div key={key} className="flex flex-col items-center gap-1 rounded-lg bg-blue-mist/60 py-2.5">
              <Icon className="h-5 w-5 text-blue" />
              <p className="text-lg font-black leading-none text-ink">{value}</p>
              <p className="text-[11px] text-muted">{label}</p>
            </div>
          ))}
        </div>
      )}
    </section>
  )
}

function IconPawMark() {
  return (
    <svg viewBox="0 0 48 48" aria-hidden="true" className="absolute -right-2 -top-2 h-16 w-16 rotate-12 text-blue-soft" fill="currentColor">
      <ellipse cx="24" cy="30" rx="7.6" ry="6.4" />
      <ellipse cx="14.2" cy="20.6" rx="3.3" ry="4.2" />
      <ellipse cx="20.6" cy="15.7" rx="3.15" ry="4.3" />
      <ellipse cx="27.4" cy="15.7" rx="3.15" ry="4.3" />
      <ellipse cx="33.8" cy="20.6" rx="3.3" ry="4.2" />
    </svg>
  )
}

/** 托管进行中：数据源=当前列表页内 boarding 非空项（无独立进度 API，不放假时间线）。 */
function BoardingCard({ block, onNotReady }: { block: Block<PageData<PetItem>>; onNotReady: (label: string) => () => void }) {
  return (
    <Card title="托管进行中" onAction={onNotReady('托管列表')}>
      {block.state === 'loading' && (
        <p className="py-6 text-center text-xs text-muted">
          <Spinner /> 加载中…
        </p>
      )}
      {block.state === 'error' && <p className="py-6 text-center text-xs text-[#E8506B]">列表加载失败，无法展示托管摘要</p>}
      {block.state === 'ready' && (() => {
        const boardingPets = block.data.list.filter((p) => p.boarding)
        if (boardingPets.length === 0) return <p className="py-6 text-center text-sm text-muted">当前暂无托管中的宠物</p>
        return (
          <ul className="flex flex-col gap-3">
            {boardingPets.slice(0, 3).map((p) => {
              return (
              <li key={p.id} className="flex items-center gap-3">
                <PetAvatar name={p.name} src={mediaSrc(p.avatarUrl)} className="h-10 w-10" iconClass="h-5 w-5" />
                <div className="min-w-0 flex-1">
                  <p className="flex items-center gap-2 text-sm font-bold text-ink">
                    <span className="truncate">{p.name}</span>
                    <span
                      className="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold"
                      style={p.boarding!.status === 'CHECKED_IN' ? { background: TONE.orange.bg, color: TONE.orange.fg } : { background: TONE.purple.bg, color: TONE.purple.fg }}
                    >
                      {p.boarding!.status === 'CHECKED_IN' ? '寄养托管' : '每日护理'}
                    </span>
                  </p>
                  <p className="mt-0.5 truncate text-xs text-muted">入住 {p.boarding!.checkinDate} · 计划离店 {p.boarding!.checkoutDate}</p>
                </div>
              </li>
              )
            })}
            {BigInt(block.data.total) > block.data.list.length && (
              <li className="text-center text-[11px] text-muted">仅统计当前页宠物，完整托管列表将随托管页接入</li>
            )}
          </ul>
        )
      })()}
    </Card>
  )
}

/** FAQ：静态说明，文案与当前真实能力一致（只读已上线、写侧未接入）。 */
function FaqCard() {
  const [openId, setOpenId] = useState<string | null>(null)
  return (
    <section className="rounded-2xl bg-white p-5 shadow-soft ring-1 ring-line">
      <h2 className="mb-2 flex items-center gap-2 text-base font-bold text-ink">
        <span className="grid h-7 w-7 place-items-center rounded-full bg-[#FFF1E0] text-[#F59E0B]">
          <IconHelp className="h-4 w-4" />
        </span>
        常见问题
      </h2>
      <ul className="divide-y divide-line">
        {FAQS.map(({ id, q, a }) => (
          <li key={id}>
            <button
              type="button"
              aria-expanded={openId === id}
              onClick={() => setOpenId((v) => (v === id ? null : id))}
              className="flex w-full items-center justify-between gap-3 py-3 text-left"
            >
              <span className="flex min-w-0 items-center gap-2 text-[13px] font-semibold text-ink">
                <i className="h-1.5 w-1.5 shrink-0 rotate-45 bg-blue" />
                <span className="truncate">{q}</span>
              </span>
              <IconChevronRight className={cx('h-4 w-4 shrink-0 text-muted transition-transform', openId === id && 'rotate-90')} />
            </button>
            {openId === id && <p className="pb-3 pl-5 text-xs leading-relaxed text-muted">{a}</p>}
          </li>
        ))}
      </ul>
    </section>
  )
}

/* ---- 通用小件 ---- */

function Spinner() {
  return <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-blue border-t-transparent align-[-2px]" aria-hidden="true" />
}

function ListLoading() {
  return (
    <div className="flex flex-col gap-3" role="status" aria-label="列表加载中">
      {[0, 1].map((i) => (
        <div key={i} className="flex h-[132px] items-center justify-center rounded-xl bg-white ring-1 ring-line">
          <span className="text-xs text-muted">
            <Spinner /> 加载宠物列表…
          </span>
        </div>
      ))}
    </div>
  )
}

function ListError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-xl bg-white py-10 ring-1 ring-line">
      <p className="text-sm text-[#E8506B]">宠物列表加载失败：{message}</p>
      <button type="button" onClick={onRetry} className="h-9 rounded-lg border border-line bg-white px-5 text-xs font-semibold text-ink transition hover:border-blue hover:text-blue">
        重试
      </button>
    </div>
  )
}
