import { IconCheckCircle, IconClock, IconWallet } from '../../components/ui/icons'
import PanelGroup from '../../components/settings/PanelGroup'
import type { GroupCtl } from '../../components/settings/SettingsKit'

export const TRADE_GROUPS = { payment: 'trade-payment', refund: 'trade-refund' } as const

/** 后端强制执行、无开关。 */
const PAYMENT_RULES: [string, string][] = [
  ['支付超时自动取消', '下单后未支付的订单会被定时任务取消并释放资源（超时时长属平台参数）'],
  ['线下收款需双人复核', '前台登记后须店长确认才推进已支付（ADR-006-6），本页面不提供登记入口'],
]
const REFUND_RULES: [string, string][] = [
  ['退款审批限额', '店长只能审批金额 ≤「refund.auto_approve_limit_fen」的退款（含拒绝同样受闸），超限须由平台管理员处理；该参数当前由后端强制判定'],
  ['价格快照不可变', '订单金额取创建时快照，之后改价目不回溯历史订单'],
]

/** E 交易与退款规则：纯说明，本页无任何开关。 */
export default function TradeRulesPanel({ ctl }: { ctl: GroupCtl }) {
  const pay = TRADE_GROUPS.payment
  const ref = TRADE_GROUPS.refund
  return (
    <div className="flex flex-col gap-4">
      <PanelGroup
        id={pay}
        Icon={IconWallet}
        title="支付与收款"
        open={ctl.isOpen(pay)}
        onToggle={() => ctl.toggle(pay)}
        summary={`${PAYMENT_RULES.length} 条：超时自动取消、线下收款双人复核`}
      >
        <RuleList rules={PAYMENT_RULES} />
      </PanelGroup>
      <PanelGroup
        id={ref}
        Icon={IconClock}
        title="退款与价格"
        open={ctl.isOpen(ref)}
        onToggle={() => ctl.toggle(ref)}
        summary={`${REFUND_RULES.length} 条：审批限额、价格快照不可变`}
      >
        <RuleList rules={REFUND_RULES} />
      </PanelGroup>
    </div>
  )
}

function RuleList({ rules }: { rules: [string, string][] }) {
  return (
    <>
      <ul className="flex flex-col gap-3">
        {rules.map(([title, why]) => (
          <li key={title} className="flex items-start gap-2">
            <span className="mt-0.5 grid h-5 w-5 shrink-0 place-items-center rounded-full bg-blue-soft text-blue">
              <IconCheckCircle className="h-3.5 w-3.5" />
            </span>
            <span className="min-w-0">
              <span className="block text-[13px] font-semibold text-ink">{title}</span>
              <span className="block text-[11px] leading-relaxed text-muted">{why}</span>
            </span>
          </li>
        ))}
      </ul>
      <p className="mt-3 flex items-center gap-1.5 text-[11px] text-muted">
        <IconClock className="h-3.5 w-3.5" /> 规则由后端强制执行，本页只作说明，无开关。
      </p>
    </>
  )
}
