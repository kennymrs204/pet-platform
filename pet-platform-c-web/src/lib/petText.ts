import { BizCode } from '../api/types'
import type { PetGender, PetSize, PetSpecies, PetStatus, PetVO } from '../api/pet'

/**
 * 宠物字段的展示映射（后端只回枚举码与原始值，中文与单位在此收口，
 * 供宠物页 / 订单页 / 主页宠物切换卡共用）。
 */

export const SPECIES_LABEL: Record<PetSpecies, string> = { DOG: '犬', CAT: '猫', OTHER: '其他' }

export const SPECIES_ICON_EMOJI: Record<PetSpecies, string> = { DOG: '🐶', CAT: '🐱', OTHER: '🐾' }

export const GENDER_TEXT: Record<PetGender, string> = { MALE: '公', FEMALE: '母', UNKNOWN: '性别未登记' }

export const GENDER_SYMBOL: Record<PetGender, string> = { MALE: '♂', FEMALE: '♀', UNKNOWN: '' }

export const SIZE_LABEL: Record<PetSize, string> = { SMALL: '小型', MEDIUM: '中型', LARGE: '大型' }

export const STATUS_TEXT: Record<PetStatus, string> = {
  NORMAL: '状态良好',
  DECEASED: '已故',
  ARCHIVED: '已归档',
}

/** 状态徽章配色：NORMAL 绿、ARCHIVED 灰、DECEASED 深灰（避免用红色暗示异常）。 */
export function statusBadgeTone(status: PetStatus): string {
  if (status === 'NORMAL') return 'bg-emerald-50 text-emerald-600 ring-1 ring-emerald-200/70'
  return 'bg-warmgray text-muted ring-1 ring-navy/5'
}

/**
 * 年龄文案。birthdayApprox=true 时加「约」；无生日时不猜年龄。
 * 口径：满 12 个月计 1 岁，剩余月份 <1 时省略。
 */
export function petAgeText(birthday: string | null, approx?: boolean | null, today = new Date()): string {
  if (!birthday) return '年龄未登记'
  const born = new Date(`${birthday}T00:00:00`)
  if (Number.isNaN(born.getTime())) return '年龄未登记'
  let months = (today.getFullYear() - born.getFullYear()) * 12 + (today.getMonth() - born.getMonth())
  if (today.getDate() < born.getDate()) months -= 1
  if (months < 0) return '尚未出生'
  const prefix = approx ? '约' : ''
  if (months < 12) return `${prefix}${months}个月`
  const y = Math.floor(months / 12)
  const m = months % 12
  return m === 0 ? `${prefix}${y}岁` : `${prefix}${y}岁${m}个月`
}

export function petWeightText(weightKg: number | null): string {
  return weightKg == null ? '体重未登记' : `${Number(weightKg.toFixed(2))}kg`
}

/** C 端删除错误：保留后端预约/托管的具体阻断原因，不把权限/网络错误混成同一条。 */
export function petDeleteErrorText(code: number, message: string): string {
  if (code === BizCode.ILLEGAL_STATE_TRANSITION) {
    return message || '该宠物仍有进行中或未完成的预约或托管，暂时无法删除'
  }
  if (code === BizCode.FORBIDDEN) return '当前账号没有删除该宠物的权限'
  if (code === BizCode.NOT_FOUND || code === BizCode.PET_NOT_MANAGEABLE) {
    return '该宠物不存在或不属于当前账号'
  }
  return message || '删除失败，请稍后重试'
}

/** 卡片副标题：品种 · 性别 · 年龄（缺项自动跳过，不出现空分隔符）。 */
export function petSubtitle(pet: PetVO): string {
  return [
    pet.breed || SPECIES_LABEL[pet.species],
    pet.gender ? GENDER_TEXT[pet.gender] : null,
    petAgeText(pet.birthday, pet.birthdayApprox),
  ]
    .filter(Boolean)
    .join(' · ')
}

/** 头像缺省：无签名地址且 avatar 不是可直连 URL 时走占位图。 */
/**
 * 头像出图地址（ADR-029）：优先用后端签发的 `avatarUrl`（presigned GET，600s）。
 * `avatar` 存的是对象 key（`u-{uid}/PET_AVATAR/...`），直接当 src 必然 404；
 * 仅当它本身就是一个可直连的 URL（历史遗留/本地路径）才回落使用。
 */
export function petAvatarUrl(pet: Pick<PetVO, 'avatar'> & Partial<Pick<PetVO, 'avatarUrl'>>): string | null {
  const signed = pet.avatarUrl?.trim()
  if (signed) return signed
  const v = pet.avatar?.trim()
  if (!v || v.startsWith('u-')) return null
  return v
}
