import { useEffect, useRef, useState, type ReactNode } from 'react'
import { BizCode, BizError } from '../../api/types'
import { cx } from '../../lib/cx'
import { TONE } from '../../lib/tone'
import type { Tone } from '../../mock/dashboard'

/**
 * 设置中心共用小件（从原 SettingsPage 原样迁出，未改语义）。
 * 只有设置页用得到，故放在 settings 目录内，不污染全局 ui 层。
 */

/** 品牌深蓝主操作 */
export const NAVY = 'bg-[#1E3A5F] text-white hover:bg-[#16304F]'
/** 次操作：白底描边 */
export const GHOST = 'bg-white text-ink ring-1 ring-line hover:text-blue hover:ring-blue'
export const INPUT = 'h-9 w-full rounded-lg bg-white px-3 text-sm text-ink ring-1 ring-line placeholder:text-muted focus:outline-none focus:ring-blue'

export function Labeled({ label, required, className, children }: { label: string; required?: boolean; className?: string; children: ReactNode }) {
  return (
    <label className={cx('flex min-w-0 flex-col gap-1', className)}>
      <span className="text-[11px] font-semibold text-muted">
        {label}
        {required && <span className="ml-0.5 text-[#E8506B]">*</span>}
      </span>
      {children}
    </label>
  )
}

export function Spinner() {
  return <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-blue border-t-transparent align-[-2px]" aria-hidden="true" />
}

export function Badge({ label, tone }: { label: string; tone: Tone }) {
  return (
    <span className="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold" style={{ background: TONE[tone].bg, color: TONE[tone].fg }}>
      {label}
    </span>
  )
}

/* ---- 区块加载（本页额外保留 code，用于区分「无权限」与「真故障」） ---- */

/**
 * 手风琴控件由页面统一持有（同一分类内单展开、跨分类互不干扰），
 * 面板只按自己声明的分组 id 取用，避免每个面板各存一份 open 态导致行为不一致。
 */
export interface GroupCtl {
  isOpen: (groupId: string) => boolean
  toggle: (groupId: string) => void
  setDirty: (groupId: string, dirty: boolean) => void
}

export type Block<T> =
  | { state: 'loading' }
  | { state: 'error'; message: string; code: number }
  | { state: 'ready'; data: T; refreshing?: boolean; refreshError?: string }

export function useAsync<T>(fetcher: () => Promise<T>, reloadKey: unknown): { block: Block<T>; reload: () => void } {
  const [block, setBlock] = useState<Block<T>>({ state: 'loading' })
  const [tick, setTick] = useState(0)
  const seq = useRef(0)
  const lastKey = useRef<unknown>(undefined)
  const fetcherRef = useRef(fetcher)
  fetcherRef.current = fetcher

  useEffect(() => {
    if (reloadKey === null || reloadKey === undefined) return
    const my = ++seq.current
    // reloadKey 变化（如切门店）= 换了数据源，整块 loading；仅 tick 变化（onChanged 同源重取）
    // 保留旧数据渲染——重载即卸载面板会把未提交输入一起丢掉（SettingsPage ② 的承诺）。
    const keyChanged = lastKey.current !== reloadKey
    lastKey.current = reloadKey
    if (keyChanged) {
      setBlock({ state: 'loading' })
    } else {
      setBlock((prev) => (prev.state === 'ready' ? { ...prev, refreshing: true, refreshError: undefined } : prev))
    }
    fetcherRef.current().then(
      (data) => {
        if (my === seq.current) setBlock({ state: 'ready', data })
      },
      (err: unknown) => {
        if (my !== seq.current) return
        const biz = err instanceof BizError ? err : null
        const message = biz?.message ?? '加载失败，请重试'
        setBlock((prev) =>
          prev.state === 'ready' && prev.refreshing
            ? { ...prev, refreshing: false, refreshError: message }
            : { state: 'error', message, code: biz?.code ?? -1 },
        )
      },
    )
  }, [reloadKey, tick])

  return { block, reload: () => setTick((t) => t + 1) }
}

export function BlockShell<T>({ block, onRetry, deniedHint, children }: { block: Block<T>; onRetry: () => void; deniedHint: string; children: (data: T) => ReactNode }) {
  if (block.state === 'loading') {
    return (
      <div className="flex h-24 items-center justify-center gap-2 rounded-2xl bg-white text-xs text-muted shadow-soft ring-1 ring-line">
        <Spinner /> 加载中…
      </div>
    )
  }
  if (block.state === 'error') {
    const denied = block.code === BizCode.FORBIDDEN
    return (
      <div className="flex flex-col items-center gap-2 rounded-2xl bg-white px-4 py-6 text-center shadow-soft ring-1 ring-line">
        <p className={cx('text-sm', denied ? 'text-muted' : 'text-[#E8506B]')}>{denied ? '本区块对当前账号不可见' : `加载失败：${block.message}`}</p>
        <p className="text-[11px] leading-relaxed text-muted">{denied ? deniedHint : '请确认后端已部署本轮端点（需重建并重启 app 容器）'}</p>
        {!denied && (
          <button type="button" onClick={onRetry} className="mt-1 h-8 rounded-lg border border-line px-4 text-xs font-semibold text-ink hover:border-blue hover:text-blue">
            重试
          </button>
        )}
      </div>
    )
  }
  return (
    <>
      {block.refreshError && (
        <p className="rounded-xl bg-[#FDF0F3] px-3 py-2 text-[11px] font-semibold text-[#C63B57]">
          刷新失败，当前显示的是上次数据：{block.refreshError}
        </p>
      )}
      {children(block.data)}
    </>
  )
}
