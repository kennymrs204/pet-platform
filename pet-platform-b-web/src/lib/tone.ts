import type { Tone } from '../mock/dashboard'

/** 效果图色板：彩色圆底/文字统一映射（B 端工作台与业务页共用） */
export const TONE: Record<Tone, { bg: string; fg: string }> = {
  blue: { bg: '#EAF1FF', fg: '#2E6BFF' },
  green: { bg: '#E0F5E9', fg: '#2FA96B' },
  purple: { bg: '#F1E8FF', fg: '#8B5CF6' },
  orange: { bg: '#FFF1E0', fg: '#F59E0B' },
  pink: { bg: '#FFE9EE', fg: '#E8506B' },
  gray: { bg: '#F3F4F6', fg: '#6B7280' },
}
