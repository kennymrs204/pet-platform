/**
 * 图片 src 取值闸：ADR-029 之后，对象存储字段的值是**裸对象 key**（`u-{userId}/{scene}/{yyyyMM}/{id}.jpg`），
 * 只有 presigned 的 `*Url` 字段才是浏览器可访问地址。裸 key 直接进 `<img src>` 会被浏览器按当前站点解析成
 * `http://localhost:5174/u-…/PET_AVATAR/…` → 404 裂图（B 端 /pets、/boarding 实测踩过）。
 * 门店侧宠物 VO 目前只给 key、没给签名地址，所以这里一律拦成 null，由调用方渲染占位。
 */
export function mediaSrc(value: string | null | undefined): string | null {
  if (!value) return null
  return /^https?:\/\//i.test(value) || value.startsWith('/') ? value : null
}
