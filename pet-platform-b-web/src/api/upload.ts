import { http } from './client'

/**
 * 本地图片直传 MinIO（ADR-029；P7.2 起 B 端复刻 C 端同款基础设施）。
 * 两步：① 后端发一次性上传凭证；② 浏览器用 uploadUrl 直传——presigned URL 的 host
 * 参与 SigV4 签名，必须整条原样使用。
 *
 * - 业务端点存 **key**（读取时由后端视图出口签 600s GET URL）；
 * - 门店头：intent 走统一 http 封装，`/uploads/intent` 属门店业务路径 →
 *   client 自动注入 X-Store-Id（B 端绝不自行决定 storeId）；
 * - CHAT_IMAGE 必须带 conversationId：服务端按会话归属授权（10003/10004）。
 */
export type UploadScene = 'CHAT_IMAGE' | 'WALK_PHOTO'

const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp']
const MAX_BYTES = 5 * 1024 * 1024

export class UploadError extends Error {}

interface IntentView {
  key: string
  uploadUrl: string
  expireIn: number
}

/** 上传前拦截（MIME/≤5MB），避免白跑一次 intent 才发现文件不合规。 */
export function validateImage(file: File): string | null {
  if (!ALLOWED_TYPES.includes(file.type)) return '只支持 JPG / PNG / WebP 图片'
  if (file.size === 0) return '图片是空的，换一张试试'
  if (file.size > MAX_BYTES) return `图片不超过 ${MAX_BYTES / 1024 / 1024}MB（当前 ${(file.size / 1024 / 1024).toFixed(1)}MB）`
  return null
}

/**
 * @returns 业务端点要存的 mediaKey
 * @param conversationId 仅 CHAT_IMAGE 必填（服务端按会话归属授权）；WALK_PHOTO 等场景不传
 */
export async function uploadImage(
  file: File,
  scene: UploadScene,
  conversationId?: string | null,
  signal?: AbortSignal,
): Promise<string> {
  const bad = validateImage(file)
  if (bad) throw new UploadError(bad)

  const intent = await http.post<IntentView>(
    '/uploads/intent',
    {
      scene,
      ...(conversationId ? { conversationId } : {}),
      contentType: file.type,
      contentLength: file.size,
    },
    { auth: true, signal },
  )
  const res = await fetch(intent.uploadUrl, {
    method: 'PUT',
    headers: { 'Content-Type': file.type },
    body: file,
    signal,
  })
  if (!res.ok) throw new UploadError(`上传失败（HTTP ${res.status}），请重试`)
  return intent.key
}
