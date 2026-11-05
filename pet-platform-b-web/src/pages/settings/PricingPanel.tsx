import { useEffect, useState, type ReactNode } from 'react'
import { IconBulb, IconWallet } from '../../components/ui/icons'
import PanelGroup from '../../components/settings/PanelGroup'
import { Badge, BlockShell, GHOST, INPUT, Labeled, NAVY, type Block, type GroupCtl } from '../../components/settings/SettingsKit'
import { useToast } from '../../components/ui/Toast'
import {
  PICK_MODES,
  PICK_MODE_LABEL,
  buildStoreServiceUpdate,
  pickModeOfOverride,
  settingsApi,
  type PickMode,
  type PricingDraft,
  type StoreServiceItem,
} from '../../api/settings'
import type { PageData } from '../../api/dashboard'
import { BizError } from '../../api/types'
import { cx } from '../../lib/cx'

export const PRICING_GROUPS = { list: 'pricing-list', semantics: 'pricing-semantics' } as const

/**
 * C 服务与价格：本店已授权上架的服务逐行定价。
 * 服务清单与名称一律来自 `/store/store-services`，不按固定服务名硬编码——
 * 本店上架了什么就渲染什么，档案被删的行服务端给的就是 null（显示「服务已下架」）。
 */
export default function PricingPanel({ ctl, block, onRetry, onReload }: { ctl: GroupCtl; block: Block<PageData<StoreServiceItem>>; onRetry: () => void; onReload: () => void }) {
  return (
    <BlockShell block={block} onRetry={onRetry} deniedHint="需要 catalog:store_service:manage（店长/平台管理员）">
      {(page: PageData<StoreServiceItem>) => <PricingBoard ctl={ctl} items={page.list} onChanged={onReload} />}
    </BlockShell>
  )
}

function PricingBoard({ ctl, items, onChanged }: { ctl: GroupCtl; items: StoreServiceItem[]; onChanged: () => void }) {
  const { push } = useToast()
  const [draft, setDraft] = useState<Record<string, PricingDraft>>({})
  const [busy, setBusy] = useState<string | null>(null)
  const [pendingOff, setPendingOff] = useState<string | null>(null)

  const listId = PRICING_GROUPS.list
  const docId = PRICING_GROUPS.semantics

  const yuanText = (it: StoreServiceItem) => (Number(BigInt(it.price)) / 100).toString()
  const d = (it: StoreServiceItem): PricingDraft =>
    draft[it.id] ?? { price: yuanText(it), durationMin: String(it.durationMin), pick: pickModeOfOverride(it.allowUserPickEmployeeOverride) }
  const isRowDirty = (it: StoreServiceItem) => {
    const cur = d(it)
    return cur.price !== yuanText(it) || cur.durationMin !== String(it.durationMin) || cur.pick !== pickModeOfOverride(it.allowUserPickEmployeeOverride)
  }
  const dirtyCount = items.filter(isRowDirty).length

  useEffect(() => {
    ctl.setDirty(listId, dirtyCount > 0)
  }, [ctl, listId, dirtyCount])

  const clearDraft = (id: string) =>
    setDraft((m) => {
      const next = { ...m }
      delete next[id]
      return next
    })

  const save = async (it: StoreServiceItem) => {
    const cur = d(it)
    const fen = Math.round(Number(cur.price) * 100)
    const dur = Number(cur.durationMin)
    if (!Number.isFinite(fen) || fen < 0) {
      push('价格需为非负数字', 'error')
      return
    }
    if (!Number.isFinite(dur) || dur < 30 || dur % 30 !== 0) {
      push('服务时长需为 30 分钟以上的整数（后端按 30 倍数校验）', 'error')
      return
    }
    setBusy(it.id)
    try {
      await settingsApi.updateStoreService(it.id, buildStoreServiceUpdate(it, cur, it.status))
      push(`${it.serviceName ?? '该服务'} 价目已更新`, 'success')
      clearDraft(it.id)
      onChanged()
    } catch (err) {
      push(err instanceof BizError ? err.message : '更新失败', 'error')
    } finally {
      setBusy(null)
    }
  }

  const submitToggle = async (it: StoreServiceItem) => {
    const next = it.status === 'ON' ? 'OFF' : 'ON'
    setBusy(it.id)
    setPendingOff(null)
    try {
      await settingsApi.updateStoreService(it.id, buildStoreServiceUpdate(it, d(it), next))
      push(`${it.serviceName ?? '服务'} 已${next === 'OFF' ? '下架' : '上架'}`, 'success')
      clearDraft(it.id)
      onChanged()
    } catch (err) {
      push(err instanceof BizError ? err.message : '上下架失败', 'error')
    } finally {
      setBusy(null)
    }
  }

  const onToggle = (it: StoreServiceItem) => {
    if (it.status === 'ON') {
      setPendingOff(it.id) // 下架是影响顾客可下单面的动作，先确认
      return
    }
    void submitToggle(it)
  }

  const onShelf = items.filter((it) => it.status === 'ON').length

  return (
    <div className="flex flex-col gap-4">
      <PanelGroup
        id={listId}
        Icon={IconWallet}
        title="本店价目"
        open={ctl.isOpen(listId)}
        dirty={dirtyCount > 0}
        onToggle={() => ctl.toggle(listId)}
        meta={<Badge label={`已上架 ${onShelf}/${items.length}`} tone={onShelf === 0 ? 'orange' : 'green'} />}
        summary={`共 ${items.length} 项服务 · 已上架 ${onShelf} 项${dirtyCount > 0 ? ` · ${dirtyCount} 行待保存` : ''}`}
      >
        {items.length === 0 ? (
          <p className="rounded-xl bg-blue-mist/60 py-8 text-center text-sm text-muted">本店还没有上架服务（价目由平台服务目录授权后上架）</p>
        ) : (
          <ul className="grid gap-3 2xl:grid-cols-2">
            {items.map((it) => (
              <ServiceCard
                key={it.id}
                item={it}
                cur={d(it)}
                dirty={isRowDirty(it)}
                busy={busy === it.id}
                confirmOff={pendingOff === it.id}
                yuanText={yuanText(it)}
                onEdit={(next) => setDraft((m) => ({ ...m, [it.id]: next }))}
                onSave={() => void save(it)}
                onToggleShelf={() => onToggle(it)}
                onConfirmOff={() => void submitToggle(it)}
                onCancelOff={() => setPendingOff(null)}
              />
            ))}
          </ul>
        )}
      </PanelGroup>

      <PanelGroup
        id={docId}
        Icon={IconBulb}
        title="保存语义与三态规则"
        open={ctl.isOpen(docId)}
        onToggle={() => ctl.toggle(docId)}
        summary="PUT 是全量 upsert；「继承平台默认」是第三态，不是关"
      >
        <div className="flex flex-col gap-3 text-[12px] leading-relaxed text-muted">
          <p>
            保存为<span className="font-semibold text-ink">全量回传</span>：该行的取消政策、资源类型会随价格/时长一并提交（后端 PUT 是 upsert 语义）。
            <span className="font-semibold text-ink">「免取消时长」与「违约金比例」本页只读</span>，其值来自该行原值，改它们不经过本页。
          </p>
          <p>
            「可指定员工」是<span className="font-semibold text-ink">三态</span>：选「继承平台默认」会把本店这条覆盖<span className="font-semibold text-ink">清空</span>、
            重新跟随平台目录的默认值；选「允许/不允许」则是本店显式覆盖，此后平台默认再怎么改都不影响这一行。
          </p>
          <p>点「上架/下架」会把该行未保存的改动一并提交——这也是行内提示「有未保存的修改」的原因。</p>
        </div>
      </PanelGroup>
    </div>
  )
}

function ServiceCard({
  item,
  cur,
  dirty,
  busy,
  confirmOff,
  yuanText,
  onEdit,
  onSave,
  onToggleShelf,
  onConfirmOff,
  onCancelOff,
}: {
  item: StoreServiceItem
  cur: PricingDraft
  dirty: boolean
  busy: boolean
  confirmOff: boolean
  yuanText: string
  onEdit: (next: PricingDraft) => void
  onSave: () => void
  onToggleShelf: () => void
  onConfirmOff: () => void
  onCancelOff: () => void
}) {
  const perDay = item.fulfillmentType === 'MULTI_DAY'
  const isOff = item.status !== 'ON'
  return (
    <li className={cx('flex flex-col rounded-xl bg-blue-mist/60 p-3.5 ring-1 ring-line', isOff && 'bg-gray-50/70')}>
      <div className="flex flex-wrap items-start gap-x-2 gap-y-1.5">
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-black text-ink">{item.serviceName ?? '服务已下架'}</p>
          <p className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-[11px] text-muted">
            <Chip>{item.category ?? '未分类'}</Chip>
            <Chip>{perDay ? '托管（按天占用）' : '单次服务'}</Chip>
            {item.serviceCode && <Chip mono>{item.serviceCode}</Chip>}
          </p>
        </div>
        <Badge label={isOff ? '已下架' : '已上架'} tone={isOff ? 'gray' : 'green'} />
        {dirty && <Badge label="未保存" tone="orange" />}
      </div>

      <p className="mt-2 text-[11px] leading-relaxed text-muted">
        免取消 {item.cancelFreeHours}h · 违约扣 {item.cancelPenaltyRate}% · 当前生效：
        <span className="font-semibold text-ink">{item.allowUserPickEmployee ? '可指定员工' : '不可指定员工'}</span>
        {item.allowUserPickEmployeeOverride === null && '（继承平台默认）'}
      </p>

      <div className="mt-3 grid gap-3 sm:grid-cols-3">
        <Labeled label={perDay ? '每日价格（元/天）' : '价格（元）'}>
          <input className={INPUT} value={cur.price} inputMode="decimal" onChange={(e) => onEdit({ ...cur, price: e.target.value })} />
        </Labeled>
        <Labeled label={perDay ? '每单占用（分钟）' : '时长（分钟）'}>
          <input className={INPUT} value={cur.durationMin} inputMode="numeric" onChange={(e) => onEdit({ ...cur, durationMin: e.target.value })} />
        </Labeled>
        <Labeled label="可指定员工">
          <select className={INPUT} value={cur.pick} onChange={(e) => onEdit({ ...cur, pick: e.target.value as PickMode })}>
            {PICK_MODES.map((mode) => (
              <option key={mode} value={mode}>{PICK_MODE_LABEL[mode]}</option>
            ))}
          </select>
        </Labeled>
      </div>

      {perDay && (
        <p className="mt-2 text-[11px] leading-relaxed text-muted">
          托管的<span className="font-semibold text-ink">价格按 ADR-025 语义是「每日单价」</span>：N 晚订单金额 = 该值 × 晚数，不是总价。
          占用时长 1440 分钟=1 天，改它不会改变计价晚数。
        </p>
      )}

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <button type="button" disabled={busy} onClick={onSave} className={cx('h-9 rounded-lg px-3.5 text-xs font-bold transition disabled:opacity-60', NAVY)}>
          保存
        </button>
        <button type="button" disabled={busy} onClick={onToggleShelf} className={cx('h-9 rounded-lg px-3.5 text-xs font-semibold transition disabled:opacity-60', GHOST)}>
          {isOff ? '上架' : '下架'}
        </button>
        <span className="ml-auto font-mono text-[10px] text-muted">#{item.id} · 当前生效 {yuanText} 元</span>
      </div>

      {dirty && <p className="mt-2 text-right text-[11px] text-[#8A6116]">有未保存的修改（点「下架/上架」也会把本行改动一并提交）</p>}

      {confirmOff && (
        <div className="mt-2.5 rounded-xl bg-[#FDF0F3] px-3 py-2.5 ring-1 ring-[#F2C9D2]">
          <p className="text-[12px] font-bold text-[#C63B57]">下架「{item.serviceName ?? '该服务'}」？</p>
          <p className="mt-1 text-[11px] leading-relaxed text-muted">
            下架后顾客端不再看到、也不能新约该服务；<span className="font-semibold text-ink">已确认的预约不会因此被取消</span>（后端只发事件，当前无消费者）。
            取消政策按本店该行实时值计算，下架后如需受理历史单的取消，请保持该行数据不删。
          </p>
          <div className="mt-2.5 flex items-center gap-2">
            <button type="button" disabled={busy} onClick={onConfirmOff} className="h-8 rounded-full bg-[#C63B57] px-4 text-xs font-bold text-white transition hover:bg-[#A82F47] disabled:opacity-50">
              {busy ? '提交中…' : '确认下架'}
            </button>
            <button type="button" onClick={onCancelOff} className="h-8 rounded-full bg-white px-4 text-xs font-semibold text-ink ring-1 ring-line">取消</button>
          </div>
        </div>
      )}
    </li>
  )
}

function Chip({ children, mono }: { children: ReactNode; mono?: boolean }) {
  return <span className={cx('rounded-md bg-white px-1.5 py-0.5 text-[11px] font-semibold text-ink ring-1 ring-line', mono && 'font-mono')}>{children}</span>
}
