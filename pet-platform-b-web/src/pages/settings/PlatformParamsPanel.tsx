import { IconBulb } from '../../components/ui/icons'
import PanelGroup from '../../components/settings/PanelGroup'
import { Badge, BlockShell, type Block } from '../../components/settings/SettingsKit'
import { unconsumedParamKeys, type PlatformParam, type StoreSettings } from '../../api/settings'

export const PLATFORM_GROUPS = { params: 'platform-params' } as const

/** D 平台参数：一律只读（门店侧无写权，平台侧写端点全仓不存在）。 */
export default function PlatformParamsPanel({ ctl, block, onRetry }: { ctl: { isOpen: (id: string) => boolean; toggle: (id: string) => void }; block: Block<StoreSettings>; onRetry: () => void }) {
  return (
    <BlockShell block={block} onRetry={onRetry} deniedHint="需要 store:info:update 才能查看">
      {(data: StoreSettings) => <Params ctl={ctl} params={data.platformParams} />}
    </BlockShell>
  )
}

function Params({ ctl, params }: { ctl: { isOpen: (id: string) => boolean; toggle: (id: string) => void }; params: PlatformParam[] }) {
  const id = PLATFORM_GROUPS.params
  // 未生效清单现算：写死名字就会在下一个消费方接上时过期（ADR-037 就把 507 从"未生效"变成了硬限额）
  const unconsumed = unconsumedParamKeys(params)
  const live = params.length - unconsumed.length

  return (
    <PanelGroup
      id={id}
      Icon={IconBulb}
      title="平台参数"
      open={ctl.isOpen(id)}
      onToggle={() => ctl.toggle(id)}
      meta={<Badge label="只读" tone="gray" />}
      summary={`共 ${params.length} 项，其中 ${live} 项已被后端读取；「未生效」${unconsumed.length} 项`}
    >
      <ul className="divide-y divide-line overflow-hidden rounded-xl ring-1 ring-line">
        {params.map((p) => (
          <li key={p.key} className="flex flex-wrap items-center gap-x-4 gap-y-1 bg-white px-3 py-2.5">
            <span className="min-w-[220px] flex-1 truncate font-mono text-[12px] text-ink">{p.key}</span>
            <span className="w-[120px] shrink-0 truncate text-sm font-bold text-ink" title={p.value}>{p.value}</span>
            <span className="min-w-[140px] flex-1 truncate text-[11px] text-muted">{p.remark ?? '—'}</span>
            <Badge label={p.consumed ? '已生效' : '未生效'} tone={p.consumed ? 'green' : 'orange'} />
          </li>
        ))}
      </ul>
      <p className="mt-3 text-[11px] leading-relaxed text-muted">
        {unconsumed.length === 0
          ? '全部参数均已有后端读取方，展示值即系统行为。'
          : `「未生效」= 该 key 当前没有任何后端代码读取（共 ${unconsumed.length} 项：${unconsumed.join('、')}），展示值不代表系统行为。`}
        {' '}标记由后端按真实引用给出，本页只转述；门店侧一律只读。
      </p>
    </PanelGroup>
  )
}
