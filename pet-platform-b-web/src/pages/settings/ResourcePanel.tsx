import { useEffect, useRef, useState, type ChangeEvent } from 'react'
import { IconBox, IconHelp, IconPlus } from '../../components/ui/icons'
import PanelGroup from '../../components/settings/PanelGroup'
import { Badge, BlockShell, INPUT, Labeled, NAVY, type Block, type GroupCtl } from '../../components/settings/SettingsKit'
import { useToast } from '../../components/ui/Toast'
import {
  RESOURCE_STATUS_LABEL,
  RESOURCE_STATUSES,
  RESOURCE_TYPES,
  RESOURCE_TYPE_LABEL,
  isDuplicateResource,
  settingsApi,
  type ResourceItem,
  type ResourceStatus,
} from '../../api/settings'
import { BizError } from '../../api/types'
import { cx } from '../../lib/cx'

export const RESOURCE_GROUPS = { ledger: 'resource-ledger', policy: 'resource-policy' } as const

/**
 * B 资源与笼位：新增 + 台账 + 状态切换（不提供删除，这是既有裁决）。
 * 状态置为非「可用」仍需二次确认——入住办理硬校验 `status=AVAILABLE`。
 */
export default function ResourcePanel({ ctl, block, onRetry, onReload }: { ctl: GroupCtl; block: Block<ResourceItem[]>; onRetry: () => void; onReload: () => void }) {
  return (
    <BlockShell block={block} onRetry={onRetry} deniedHint="需要 resource:manage（店长/平台管理员）">
      {(items: ResourceItem[]) => <ResourcesPanel ctl={ctl} items={items} onChanged={onReload} />}
    </BlockShell>
  )
}

function ResourcesPanel({ ctl, items, onChanged }: { ctl: GroupCtl; items: ResourceItem[]; onChanged: () => void }) {
  const { push } = useToast()
  const [type, setType] = useState<string>(RESOURCE_TYPES[0])
  const [name, setName] = useState('')
  const inputRef = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState<string | null>(null)
  /** 置为非 AVAILABLE 是"当天可能办不了入住"的动作，先落待确认态，不直接提交 */
  const [pending, setPending] = useState<{ id: string; status: string } | null>(null)

  const ledgerId = RESOURCE_GROUPS.ledger
  const policyId = RESOURCE_GROUPS.policy
  const draftPending = name.trim() !== ''

  useEffect(() => {
    ctl.setDirty(ledgerId, draftPending)
  }, [ctl, ledgerId, draftPending])

  const add = async () => {
    // 以输入框 DOM 实际值为准兜底：state 与 DOM 脱同步时（如输入法组合未上屏、刷新窗口重挂载）按用户看到的字提交
    const trimmed = (inputRef.current?.value ?? name).trim()
    if (!trimmed) {
      push('请先填写资源名称', 'error')
      return
    }
    if (isDuplicateResource(items, type, trimmed)) {
      push(`已存在同类型的「${trimmed}」，请换名字或直接用现有资源`, 'error')
      return
    }
    setBusy('new')
    try {
      await settingsApi.createResource(type, trimmed)
      push('资源已新增（默认可用）', 'success')
      setName('')
      if (inputRef.current) inputRef.current.value = ''
      onChanged()
    } catch (err) {
      push(err instanceof BizError ? err.message : '新增失败', 'error')
    } finally {
      setBusy(null)
    }
  }

  const changeStatus = async (r: ResourceItem, status: string) => {
    setBusy(r.id)
    setPending(null)
    try {
      await settingsApi.updateResource(r.id, { status })
      push(`${r.name} 已置为${RESOURCE_STATUS_LABEL[status as ResourceStatus] ?? status}`, 'success')
      onChanged()
    } catch (err) {
      push(err instanceof BizError ? err.message : '状态修改失败', 'error')
    } finally {
      setBusy(null)
    }
  }

  const onPick = (r: ResourceItem, status: string) => {
    if (status === r.status) return
    if (status === 'AVAILABLE') {
      void changeStatus(r, status)
      return
    }
    setPending({ id: r.id, status })
  }

  const available = items.filter((r) => r.status === 'AVAILABLE').length

  return (
    <div className="flex flex-col gap-4">
      <PanelGroup
        id={ledgerId}
        Icon={IconBox}
        title="资源台账"
        open={ctl.isOpen(ledgerId)}
        dirty={draftPending}
        onToggle={() => ctl.toggle(ledgerId)}
        meta={<Badge label={`可用 ${available}/${items.length}`} tone={items.length === 0 ? 'orange' : 'green'} />}
        summary={`共 ${items.length} 项资源 · 可用 ${available} 项 · 新增入口与状态切换在此`}
      >
        <div className="flex flex-col gap-4">
          <div className="flex flex-wrap items-end gap-3">
            <Labeled label="资源类型" className="w-[150px]">
              <select className={INPUT} value={type} onChange={(e: ChangeEvent<HTMLSelectElement>) => setType(e.target.value)}>
                {RESOURCE_TYPES.map((t) => (
                  <option key={t} value={t}>{RESOURCE_TYPE_LABEL[t]}</option>
                ))}
              </select>
            </Labeled>
            <Labeled label="资源名称" className="min-w-[180px] flex-1">
              <input ref={inputRef} className={INPUT} value={name} onChange={(e) => setName(e.target.value)} maxLength={50} placeholder="如：中笼位 A3" />
            </Labeled>
            <button type="button" disabled={busy === 'new'} onClick={() => void add()} className={cx('flex h-9 items-center gap-1.5 rounded-lg px-4 text-xs font-bold transition disabled:opacity-60', NAVY)}>
              <IconPlus className="h-4 w-4" /> 新增资源
            </button>
          </div>

          {items.length === 0 ? (
            <p className="rounded-xl bg-blue-mist/60 py-8 text-center text-sm text-muted">本店还没有资源，先用上方表单添加笼位或洗澡台</p>
          ) : (
            <ul className="divide-y divide-line overflow-hidden rounded-xl ring-1 ring-line">
              {items.map((r) => (
                <li key={r.id} className="bg-white px-3 py-2.5">
                  <div className="flex flex-wrap items-center gap-3">
                    <span className="w-[86px] shrink-0 rounded-md bg-blue-mist/60 px-2 py-0.5 text-center text-[11px] font-semibold text-ink">
                      {RESOURCE_TYPE_LABEL[r.type as keyof typeof RESOURCE_TYPE_LABEL] ?? r.type}
                    </span>
                    <span className="min-w-[120px] flex-1 truncate text-sm font-bold text-ink">{r.name}</span>
                    <span className="font-mono text-[11px] text-muted">#{r.id}</span>
                    <select
                      value={r.status}
                      disabled={busy === r.id}
                      onChange={(e) => onPick(r, e.target.value)}
                      aria-label={`${r.name} 状态`}
                      className="h-8 rounded-lg bg-white px-2 text-xs font-semibold text-ink ring-1 ring-line focus:outline-none focus:ring-blue"
                    >
                      {RESOURCE_STATUSES.map((s) => (
                        <option key={s} value={s}>{RESOURCE_STATUS_LABEL[s]}</option>
                      ))}
                    </select>
                  </div>
                  {pending?.id === r.id && (
                    <div className="mt-2.5 rounded-xl bg-[#FDF0F3] px-3 py-2.5 ring-1 ring-[#F2C9D2]">
                      <p className="text-[12px] font-bold text-[#C63B57]">把「{r.name}」置为{RESOURCE_STATUS_LABEL[pending.status as ResourceStatus] ?? pending.status}？</p>
                      <p className="mt-1 text-[11px] leading-relaxed text-muted">{statusConsequence(r, pending.status)}</p>
                      <div className="mt-2.5 flex items-center gap-2">
                        <button type="button" disabled={busy === r.id} onClick={() => void changeStatus(r, pending.status)} className={cx('h-8 rounded-full px-4 text-xs font-bold text-white transition disabled:opacity-50', 'bg-[#C63B57] hover:bg-[#A82F47]')}>
                          {busy === r.id ? '提交中…' : '确认修改'}
                        </button>
                        <button type="button" onClick={() => setPending(null)} className="h-8 rounded-full bg-white px-4 text-xs font-semibold text-ink ring-1 ring-line">取消</button>
                      </div>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      </PanelGroup>

      <PanelGroup
        id={policyId}
        Icon={IconHelp}
        title="为什么没有删除按钮"
        open={ctl.isOpen(policyId)}
        onToggle={() => ctl.toggle(policyId)}
        summary="占用记录指向资源行，物理删会让历史悬空 ⇒ 只给停用"
      >
        <div className="flex flex-col gap-3 text-[12px] leading-relaxed text-muted">
          <p>
            资源<span className="font-semibold text-ink">不提供删除</span>：占用记录指向资源行，物理删会让历史占用悬空。不再使用的资源请置为「已停用」。
            资源存在当前或未来占用时，<span className="font-semibold text-ink">后端会拒绝停用或设为维护中</span>，不会自动取消、迁移或改派已有业务。
          </p>
          <ul className="flex flex-col gap-2 rounded-xl bg-blue-mist/60 p-3">
            <li>
              <span className="font-semibold text-ink">笼位类（小/中/大笼位、猫舍）</span>
              ：存在当前或未来占用时系统会拒绝停用/维护；已有住单的预占不会被取消或改派。
            </li>
            <li>
              <span className="font-semibold text-ink">台位类（洗澡台、美容台、诊室）</span>
              ：存在当前或未来占用时系统会拒绝停用/维护；已有台位占用不会被清除。
            </li>
          </ul>
          <p>没有当前或未来占用时可直接停用；恢复「可用」即可重新参与判定。</p>
        </div>
      </PanelGroup>
    </div>
  )
}

/** 说清停用会发生什么——判据来自后端占用保护，不是主观提示。 */
function statusConsequence(r: ResourceItem, status: string): string {
  const isKennel = r.type === 'KENNEL_SMALL' || r.type === 'KENNEL_MEDIUM' || r.type === 'KENNEL_LARGE' || r.type === 'CAT_ROOM'
  const action = status === 'DISABLED' ? '停用' : '设为维护中'
  if (isKennel) {
    return `资源存在当前或未来占用时，系统将拒绝${action}；已有托管预占不会被取消或改派。`
  }
  return `资源存在当前或未来占用时，系统将拒绝${action}；已有台位占用不会被清除。`
}
