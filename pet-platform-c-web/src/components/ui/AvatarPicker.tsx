import { useRef, useState } from 'react'
import { UploadError, uploadImage, type UploadScene } from '../../api/upload'
import { cx } from '../../lib/cx'
import { IconPlus } from './icons'

/**
 * 本地图片选择 + 直传 MinIO（ADR-029）。回传给父组件的是**对象 key**，不是 URL——
 * 业务端点存 key，读侧由后端签 600s 的 GET URL。
 *
 * 上传成功后立即用 `createObjectURL` 出图（是用户刚选的那张真图，非占位素材），
 * 保存后下次进页面就换成后端签发的 avatarUrl。
 */
export default function AvatarPicker({
  scene,
  value,
  previewUrl,
  onPicked,
  onError,
  className,
  label = '换头像',
}: {
  scene: UploadScene
  value: string | null
  previewUrl?: string | null
  onPicked: (key: string | null, localUrl: string | null) => void
  onError: (msg: string) => void
  className?: string
  label?: string
}) {
  const input = useRef<HTMLInputElement>(null)
  const [localUrl, setLocalUrl] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const url = localUrl ?? previewUrl ?? null

  const pick = async (file: File | undefined) => {
    if (!file) return
    setBusy(true)
    try {
      const key = await uploadImage(file, scene)
      const objUrl = URL.createObjectURL(file)
      setLocalUrl(objUrl)
      onPicked(key, objUrl)
    } catch (e) {
      onError(e instanceof UploadError ? e.message : '上传失败，请稍后再试')
    } finally {
      setBusy(false)
      if (input.current) input.current.value = ''
    }
  }

  return (
    <div className={cx('relative shrink-0', className)}>
      <button
        type="button"
        onClick={() => input.current?.click()}
        disabled={busy}
        className="group relative grid h-20 w-20 place-items-center overflow-hidden rounded-2xl bg-white/70 ring-1 ring-navy/10 transition active:scale-[0.98] disabled:opacity-60"
        aria-label={label}
      >
        {url ? (
          <img src={url} alt="" className="h-full w-full object-cover" />
        ) : (
          <IconPlus className="h-7 w-7 text-navy/30" />
        )}
        <span className="absolute inset-x-0 bottom-0 bg-navy/55 py-0.5 text-[10px] font-semibold text-white opacity-0 transition group-hover:opacity-100">
          {busy ? '上传中…' : label}
        </span>
      </button>
      {value && !busy && (
        <button
          type="button"
          onClick={() => {
            setLocalUrl(null)
            onPicked(null, null)
          }}
          className="absolute -right-1 -top-1 grid h-5 w-5 place-items-center rounded-full bg-navy/70 text-[11px] leading-none text-white"
          aria-label="移除图片"
        >
          ×
        </button>
      )}
      <input
        ref={input}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        aria-label={label}
        tabIndex={-1}
        className="sr-only"
        onChange={(e) => void pick(e.target.files?.[0])}
      />
      {busy && <span className="sr-only">正在上传</span>}
    </div>
  )
}
