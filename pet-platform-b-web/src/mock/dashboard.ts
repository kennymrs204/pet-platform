import type { ComponentType, SVGProps } from 'react'
import {
  IconBath,
  IconBox,
  IconChart,
  IconClipboard,
  IconDog,
  IconGear,
  IconHeart,
  IconHeartPulse,
  IconHouse,
  IconPaw,
  IconShield,
  IconStar,
  IconUser,
  IconUsers,
  IconChat,
} from '../components/ui/icons'

export type IconComp = ComponentType<SVGProps<SVGSVGElement>>

/**
 * B 端静态 UI 常量（导航/快速操作/横幅/信任徽章）。
 * 首页四大数据区块已接真实接口（ADR-013，src/api/dashboard.ts），原演示数据集已删除。
 */

/** 身份接口未就绪/无姓名时的中性回退文案（Layout 顶栏用），非演示数据 */
export const MANAGER = { name: '宠物管家', role: '员工' }

export interface NavItem {
  key: string
  label: string
  Icon: IconComp
}

export const NAV_ITEMS: NavItem[] = [
  { key: 'home', label: '首页', Icon: IconHouse },
  { key: 'pets', label: '宠物管理', Icon: IconPaw },
  { key: 'boarding', label: '托管服务', Icon: IconBox },
  { key: 'walking', label: '遛狗服务', Icon: IconDog },
  { key: 'care', label: '宠物照顾', Icon: IconHeart },
  { key: 'bathing', label: '洗澡服务', Icon: IconBath },
  { key: 'tasks', label: '我的任务', Icon: IconClipboard },
  { key: 'employees', label: '员工管理', Icon: IconUser },
  { key: 'orders', label: '订单管理', Icon: IconClipboard },
  { key: 'customers', label: '客户管理', Icon: IconUsers },
  { key: 'chat', label: '客户沟通', Icon: IconChat },
  { key: 'reports', label: '统计报表', Icon: IconChart },
  { key: 'settings', label: '设置', Icon: IconGear },
]

export type Tone = 'blue' | 'green' | 'purple' | 'orange' | 'pink' | 'gray'

export interface QuickAction {
  key: string
  label: string
  Icon: IconComp
  tone: Tone
}

/** 快捷动作字典（key 与 HomePage 的 ACTION_ROUTES 对齐）；「安排遛狗」暂无落地页 */
export const QUICK_ACTIONS: QuickAction[] = [
  { key: 'new-boarding', label: '新增寄养', Icon: IconBox, tone: 'purple' },
  { key: 'new-walking', label: '安排遛狗', Icon: IconPaw, tone: 'green' },
  { key: 'new-bathing', label: '预约洗澡', Icon: IconBath, tone: 'blue' },
  { key: 'view-orders', label: '查看订单', Icon: IconClipboard, tone: 'orange' },
]

export const BANNER_TAGS: { label: string; Icon: IconComp }[] = [
  { label: '安全', Icon: IconShield },
  { label: '专业', Icon: IconStar },
  { label: '贴心', Icon: IconHeart },
]

export const TRUST_BADGES: { label: string; Icon: IconComp; tone: Tone }[] = [
  { label: '专业团队', Icon: IconUsers, tone: 'blue' },
  { label: '安全保障', Icon: IconShield, tone: 'green' },
  { label: '贴心服务', Icon: IconHeart, tone: 'pink' },
  { label: '健康关爱', Icon: IconHeartPulse, tone: 'orange' },
]
