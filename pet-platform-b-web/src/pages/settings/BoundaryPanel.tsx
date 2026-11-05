import { IconHelp, IconShield } from '../../components/ui/icons'
import PanelGroup from '../../components/settings/PanelGroup'
import type { GroupCtl } from '../../components/settings/SettingsKit'

export const BOUNDARY_GROUPS = { scope: 'boundary-scope' } as const

const NOT_READY: [string, string][] = [
  ['门店 logo / 封面 / 简介', '库里无对应列，需先补表设计与 MinIO 接线'],
  ['协议与隐私文案', '无内容表，C 端登录页目前也是占位链接'],
  ['通知偏好开关', '通知域只有收件箱表（ntf_notification），无订阅配置表'],
  ['平台参数修改', '门店侧只读；平台侧写端点尚未实现，当前只能由运维直接改库'],
  ['员工角色自定义', '角色为种子固定的 8 个，权限映射无管理界面'],
]

/** 能力边界：把"这页做不到什么、为什么"留在页面上，而不是让用户点了才发现。 */
export default function BoundaryPanel({ ctl }: { ctl: GroupCtl }) {
  const id = BOUNDARY_GROUPS.scope
  return (
    <PanelGroup
      id={id}
      Icon={IconHelp}
      title="本页能力边界"
      open={ctl.isOpen(id)}
      onToggle={() => ctl.toggle(id)}
      summary={`${NOT_READY.length} 项未实现及其原因；先看清再提需求`}
    >
      <ul className="flex flex-col gap-2.5 text-[13px]">
        <li className="flex items-start gap-2">
          <span className="mt-1 h-1.5 w-1.5 shrink-0 rotate-45 bg-[#2FA96B]" />
          <span className="text-ink">
            已支持：门店资料与营业时间（立即影响可约时段）、公告（存库，顾客端暂未展示）、资源台账、本店价目与上下架、平台参数只读、修改密码
          </span>
        </li>
        {NOT_READY.map(([label, why]) => (
          <li key={label} className="flex items-start gap-2">
            <span className="mt-1 h-1.5 w-1.5 shrink-0 rotate-45 bg-[#E8506B]" />
            <span className="min-w-0 flex-1">
              <span className="font-semibold text-ink">{label}</span>
              <span className="block text-[11px] text-muted">{why}</span>
            </span>
          </li>
        ))}
      </ul>
      <p className="mt-3 flex items-start gap-1.5 text-[11px] leading-relaxed text-muted">
        <IconShield className="mt-0.5 h-3.5 w-3.5 shrink-0" />
        经营状态与门店类型不在本页可改字段内——前者是停业动作，后者改了等于给自己提权。
      </p>
    </PanelGroup>
  )
}
