import { http } from './client'

/**
 * 本地图片直传 MinIO（ADR-029 方案二：私有桶 + presigned PUT，图片不过后端）。
 *
 * 两步：① 向后端要一次性上传凭证（scene 白名单 + 类型/大小校验在 intent 阶段做）；
 *      ② 用返回的 uploadUrl 直接 PUT 文件体——**presigned URL 的 host 参与 SigV4 签名，
 *      不可改写**，只能整条原样使用（改 host 即签名失效）。
 *
 * 成功返回 **key**（如 `u-{uid}/USER_AVATAR/202609/123.jpg`），业务端点存的是 key，
 * 读取时由视图出参的 `avatarUrl`/`photoUrls` 给签名 URL（600s 时效，刷新页面即重签）。
 */
export type UploadScene = 'USER_AVATAR' | 'PET_AVATAR' | 'PET_ALBUM' | 'CARE_PHOTO' | 'CHAT_IMAGE'

const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp']
const MAX_BYTES = 5 * 1024 * 1024

export class UploadError extends Error {}

interface IntentView {
  key: string
  uploadUrl: string
  expireIn: number
}

/** 上传前拦截，避免白跑一次 intent 才发现文件不合规。 */
export function validateImage(file: File): string | null {
  if (!ALLOWED_TYPES.includes(file.type)) return '只支持 JPG / PNG / WebP 图片'
  if (file.size === 0) return '图片是空的，换一张试试'
  if (file.size > MAX_BYTES) return `图片不超过 ${MAX_BYTES / 1024 / 1024}MB（当前 ${(file.size / 1024 / 1024).toFixed(1)}MB）`
  return null
}

/**
 * @param conversationId 仅 CHAT_IMAGE 必填：服务端按会话归属授权（C=本人会话；B=本店会话），
 *   不接受"先拿凭证再挑会话"；缺失时后端 10001，非本人会话 10004。
 * @returns 业务端点要存的 key
 */
export async function uploadImage(
  file: File,
  scene: UploadScene,
  conversationId?: string,
  signal?: AbortSignal,
): Promise<string> {
  const bad = validateImage(file)
  if (bad) throw new UploadError(bad)

  const body: { scene: UploadScene; contentType: string; contentLength: number; conversationId?: string } = {
    scene,
    contentType: file.type,
    contentLength: file.size,
  }
  if (conversationId) body.conversationId = conversationId

  const intent = await http.post<IntentView>('/uploads/intent', body, { auth: true, signal })
  const res = await fetch(intent.uploadUrl, {
    method: 'PUT',
    headers: { 'Content-Type': file.type },
    body: file,
    signal,
  })
  if (!res.ok) throw new UploadError(`上传失败（HTTP ${res.status}），请重试`)
  return intent.key
}
