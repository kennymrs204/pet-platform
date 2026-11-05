import { useState } from 'react'
import type { PetVO } from '../../api/pet'
import { petAvatarUrl } from '../../lib/petText'
import { cx } from '../../lib/cx'
import { IconPaw } from '../ui/icons'

/**
 * 宠物头像。出图走 petAvatarUrl：优先后端签发的 avatarUrl（ADR-029 presigned GET），
 * 无图时走爪印占位——不复用任何演示图片，避免「看起来有照片其实是假的」。
 *
 * <p>签名地址只有 600s，页面开着不动就会 403，跨设备浏览时 host 也可能不可达 →
 * 读图失败必须回落占位，否则浏览器画的是裂图图标（用户看到的"坏了"其实是这个）。
 * 记的是**失败的那个 url**而不是布尔量：列表重拉会换一枚新签名，布尔量会把新图也一起判死。
 */
export default function PetAvatar({ pet, className }: { pet: Pick<PetVO, 'avatar'>; className?: string }) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  const url = petAvatarUrl(pet)
  if (url && failedUrl !== url) {
    return (
      <img
        src={url}
        alt=""
        onError={() => setFailedUrl(url)}
        className={cx('shrink-0 rounded-2xl object-cover ring-1 ring-navy/10', className)}
        loading="lazy"
      />
    )
  }
  return (
    <span
      className={cx(
        'grid shrink-0 place-items-center rounded-2xl bg-gradient-to-br from-ivory to-warmgray ring-1 ring-navy/5',
        className,
      )}
      aria-hidden="true"
    >
      <IconPaw className="h-1/2 w-1/2 text-gold-dark/50" />
    </span>
  )
}
