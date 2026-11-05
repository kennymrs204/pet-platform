import { useCallback, useMemo, useState, useSyncExternalStore } from 'react'
import { useSearchParams } from 'react-router-dom'
import Layout from '../components/layout/Layout'
import SubNav from '../components/ui/SubNav'
import {
  IconBox,
  IconBulb,
  IconGear,
  IconHelp,
  IconLock,
  IconMapPin,
  IconShield,
  IconWallet,
} from '../components/ui/icons'
import { useToast } from '../components/ui/Toast'
import { storeContext, storeLabel } from '../auth/storeContext'
import { settingsApi } from '../api/settings'
import { useAsync, type GroupCtl } from '../components/settings/SettingsKit'
import StoreInfoPanel, { STORE_GROUPS } from './settings/StoreInfoPanel'
import ResourcePanel, { RESOURCE_GROUPS } from './settings/ResourcePanel'
import PricingPanel, { PRICING_GROUPS } from './settings/PricingPanel'
import PlatformParamsPanel, { PLATFORM_GROUPS } from './settings/PlatformParamsPanel'
import TradeRulesPanel, { TRADE_GROUPS } from './settings/TradeRulesPanel'
import AccountPanel, { ACCOUNT_GROUPS } from './settings/AccountPanel'
import BoundaryPanel, { BOUNDARY_GROUPS } from './settings/BoundaryPanel'
import { cx } from '../lib/cx'
import type { IconComp } from '../mock/dashboard'

interface Category {
  key: string
  label: string
  Icon: IconComp
  hint: string
  /** 声明顺序即优先级：每类第一个分组默认展开（同一分类内单展开） */
  groups: string[]
}

const CATEGORIES: Category[] = [
  { key: 'store', label: '门店设置', Icon: IconMapPin, hint: '门店名称、电话、地址、营业与休息时段、门店公告；营业时间改动立即影响顾客可约时段', groups: Object.values(STORE_GROUPS) },
  { key: 'resource', label: '资源与笼位', Icon: IconBox, hint: '新增资源、可用状态切换与台账口径；不提供删除，用「停用」保留占用历史', groups: Object.values(RESOURCE_GROUPS) },
  { key: 'pricing', label: '服务与价格', Icon: IconWallet, hint: '本店上架服务的定价、时长与员工指定规则（托管价=每日单价）', groups: Object.values(PRICING_GROUPS) },
  { key: 'platform', label: '平台参数', Icon: IconBulb, hint: '门店侧一律只读；平台侧的写端点尚未实现', groups: Object.values(PLATFORM_GROUPS) },
  { key: 'trade', label: '交易与退款', Icon: IconShield, hint: '后端强制执行的支付、收款、退款与快照规则说明，本页无开关', groups: Object.values(TRADE_GROUPS) },
  { key: 'account', label: '账号与安全', Icon: IconLock, hint: '当前登录身份与修改密码；改密后当前会话失效，需重新登录', groups: Object.values(ACCOUNT_GROUPS) },
  { key: 'boundary', label: '能力边界', Icon: IconHelp, hint: '这页做不到什么、为什么——先看清再提需求', groups: Object.values(BOUNDARY_GROUPS) },
]

/** 分组 id → 所属分类的全部兄弟 id（打开一个就收起同类其余，跨类互不干扰）。 */
const GROUP_SCOPE: Record<string, string[]> = Object.fromEntries(
  CATEGORIES.flatMap((c) => c.groups.map((g) => [g, c.groups])),
)

const DEFAULT_OPEN: Record<string, boolean> = Object.fromEntries(
  CATEGORIES.flatMap((c) => c.groups.map((g, i) => [g, i === 0])),
)

/**
 * B 端设置中心（ADR-020 的 UI 重构）：二级分类 + 手风琴分组。
 *
 * 三条不动的边界：
 * ① 三个 GET 仍在页面顶层发（切分类不发新请求、不 refetch，与重构前的请求时机一致）；
 * ② 所有分类面板**保持挂载**，非当前分类用 CSS 隐藏——面板内表单 state 归组件自己，
 *    卸载即丢用户输入，所以"折叠/切类"绝不能变成卸载；
 * ③ 权限与降级口径原样保留：前台/任务角色缺 store:info:update、resource:manage、
 *    catalog:store_service:manage 时进来看不到编辑权，是权限口径而非故障。
 * 全页无演示数据兜底。
 */
export default function SettingsPage() {
  const { push } = useToast()
  const ctx = useSyncExternalStore(storeContext.subscribe, storeContext.get)
  const storeId = ctx.storeId

  const info = useAsync(() => settingsApi.info(), storeId)
  const services = useAsync(() => settingsApi.storeServices(), storeId)
  const resources = useAsync(() => settingsApi.resources(), storeId)

  const [searchParams, setSearchParams] = useSearchParams()
  const active = CATEGORIES.find((c) => c.key === searchParams.get('tab'))?.key ?? 'store'

  const [openMap, setOpenMap] = useState<Record<string, boolean>>(DEFAULT_OPEN)
  const [dirtyMap, setDirtyMap] = useState<Record<string, boolean>>({})

  const isOpen = useCallback((groupId: string) => openMap[groupId] === true, [openMap])
  const setDirty = useCallback(
    (groupId: string, dirty: boolean) => setDirtyMap((m) => (m[groupId] === dirty ? m : { ...m, [groupId]: dirty })),
    [],
  )
  const toggleGroup = useCallback(
    (groupId: string) => {
      const willOpen = openMap[groupId] !== true
      if (!willOpen && dirtyMap[groupId]) push('已收起，未保存的修改仍然保留——只有点「保存」才会提交', 'info')
      setOpenMap((m) => {
        const next = { ...m }
        for (const sibling of GROUP_SCOPE[groupId] ?? []) next[sibling] = false
        next[groupId] = willOpen
        return next
      })
    },
    [openMap, dirtyMap, push],
  )
  const ctl = useMemo<GroupCtl>(() => ({ isOpen, toggle: toggleGroup, setDirty }), [isOpen, toggleGroup, setDirty])

  const dirtyKeys = CATEGORIES.filter((c) => c.groups.some((g) => dirtyMap[g])).map((c) => c.key)

  const goCategory = (key: string) => {
    if (key === active) return
    if (dirtyKeys.length > 0) {
      push(`有 ${dirtyKeys.length} 个分类含未保存的修改：切换分类不会丢弃它们，点「保存」才会提交`, 'info')
    }
    setSearchParams((prev) => { const next = new URLSearchParams(prev); next.set('tab', key); return next }, { replace: true })
  }

  const current = ctx.candidates.find((s) => s.storeId === storeId)

  return (
    <Layout active="settings">
      <div className="flex flex-col gap-5 p-4 sm:p-6">
        <div className="rounded-2xl bg-white p-5 shadow-soft ring-1 ring-line sm:p-6">
          <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
            <div className="min-w-0">
              <h1 className="flex items-center gap-2 text-[26px] font-black text-ink">
                <IconGear className="h-6 w-6 text-blue" /> 设置
              </h1>
              <p className="mt-1.5 text-sm text-muted">
                门店资料、资源台账与价目由店长/平台管理员维护；平台级参数本页只读。
              </p>
              <p className="mt-1 text-[11px] text-muted">
                数据源 <code className="font-mono">GET /store/info</code> ·{' '}
                <code className="font-mono">/store/resources</code> ·{' '}
                <code className="font-mono">/store/store-services</code>（真实，无演示兜底）
              </p>
            </div>
            {current && (
              <span className="flex h-8 shrink-0 items-center gap-1.5 rounded-full bg-blue-mist px-3 text-xs font-semibold text-ink">
                <IconMapPin className="h-3.5 w-3.5 text-blue" />
                {storeLabel(current)}
                <span className="text-muted">{ctx.platformAdmin ? '平台代管' : current.roles.join('/') || '员工'}</span>
              </span>
            )}
          </div>
        </div>

        <div className="grid gap-5 lg:grid-cols-[208px_minmax(0,1fr)]">
          <SubNav items={CATEGORIES} active={active} dirtyKeys={dirtyKeys} onSelect={goCategory} ariaLabel="设置分类" />

          <div className="flex min-w-0 flex-col gap-5">
            {CATEGORIES.map((c) => (
              <div key={c.key} className={cx('flex-col gap-4', c.key === active ? 'flex' : 'hidden')}>
                <div className="flex flex-wrap items-baseline gap-x-3">
                  <h2 className="text-lg font-black text-ink">{c.label}</h2>
                  <p className="text-xs text-muted">{c.hint}</p>
                </div>
                {c.key === 'store' && (
                  <StoreInfoPanel ctl={ctl} block={info.block} storeId={storeId} onRetry={info.reload} onReload={info.reload} />
                )}
                {c.key === 'resource' && <ResourcePanel ctl={ctl} block={resources.block} onRetry={resources.reload} onReload={resources.reload} />}
                {c.key === 'pricing' && <PricingPanel ctl={ctl} block={services.block} onRetry={services.reload} onReload={services.reload} />}
                {c.key === 'platform' && <PlatformParamsPanel ctl={ctl} block={info.block} onRetry={info.reload} />}
                {c.key === 'trade' && <TradeRulesPanel ctl={ctl} />}
                {c.key === 'account' && <AccountPanel ctl={ctl} />}
                {c.key === 'boundary' && <BoundaryPanel ctl={ctl} />}
              </div>
            ))}
          </div>
        </div>
      </div>
    </Layout>
  )
}
