import type { WalkRecordRef } from '../api/walking'

/**
 * 遛狗照片的前端规则（纯函数，便于单测）。
 *
 * 数量上限与后端 `ServiceRecordCommandApi.MAX_WALK_PHOTOS` 同值——**服务端才是硬闸**，
 * 这里只用于提前提示，避免用户传完才被拒。两处不同步时以后端为准。
 */
export const MAX_WALK_PHOTOS = 9

/** 还能再传几张（已传数量以服务端返回的 photoKeys 为准，不数本地状态）。 */
export function remainingPhotoSlots(existing: number, max: number = MAX_WALK_PHOTOS): number {
  const used = Number.isFinite(existing) && existing > 0 ? Math.floor(existing) : 0
  return Math.max(0, max - used)
}

/**
 * 选文件后的本地拦截；返回 null 表示可以进入上传。
 * 只挡"明显不合法"，`<input type=file>` 没拿到文件（用户取消）也算一次提示。
 */
export function photoPickError(fileCount: number, slots: number): string | null {
  if (fileCount <= 0) return '没有选中图片'
  if (slots <= 0) return `该记录已有 ${MAX_WALK_PHOTOS} 张照片，已达上限`
  if (fileCount > slots) return `本次最多还能添加 ${slots} 张（单条记录上限 ${MAX_WALK_PHOTOS} 张）`
  return null
}

/** 记录区展示用的照片地址（只认服务端现签的 photoUrls；签不出时为空，由 UI 回退占位）。 */
export function recordPhotoUrls(record: WalkRecordRef | null | undefined): string[] {
  const urls = record?.photoUrls
  return Array.isArray(urls) ? urls.filter((u) => typeof u === 'string' && u.length > 0) : []
}

/** 已有照片张数（与 photoKeys 对齐；key 缺失时退回 URL 数量，两者都缺=0）。 */
export function recordPhotoCount(record: WalkRecordRef | null | undefined): number {
  const keys = record?.photoKeys
  if (Array.isArray(keys)) return keys.length
  return recordPhotoUrls(record).length
}
