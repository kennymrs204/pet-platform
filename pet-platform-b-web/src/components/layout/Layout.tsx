import { useEffect, useState, useSyncExternalStore, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { authApi } from '../../api/auth'
import { tokenStore } from '../../auth/tokenStore'
import { session } from '../../auth/session'
import { storeContext, storeLabel, type StoreCtxState } from '../../auth/storeContext'
import { resetUnreadCount } from '../../lib/unreadCount'
import { resetTaskBadge, useTaskBadge } from '../../lib/taskBadge'
import { taskBadgeText } from '../../lib/taskBadgeRules'
import NotificationBell from './NotificationBell'
import { IconChevronDown, IconLogout, IconMapPin, IconUser } from '../ui/icons'
import Logo from '../ui/Logo'
import { useToast } from '../ui/Toast'
import { cx } from '../../lib/cx'
import { MANAGER, NAV_ITEMS } from '../../mock/dashboard'

/** 已实现真实路由的导航项；其余模块随后端段次点亮 */
const ROUTED: Record<string, string> = { home: '/home', pets: '/pets', boarding: '/boarding', walking: '/walking', care: '/care', customers: '/customers', chat: '/customer-chat', employees: '/employees', orders: '/orders', settings: '/settings', bathing: '/bathing', tasks: '/tasks' }

/**
 * B 端共享框架（阶段 3）：会话守卫 → 员工身份/门店上下文初始化 → 顶栏（门店切换/铃铛/账号菜单）+ 左侧导航。
 * 初始化未完成前不渲染业务 Layout（避免闪现与无上下文请求）；无员工身份进引导页而非跳登录。
 * active 由页面指定高亮项；未开放模块点击给 Toast 占位。
 */
export default function Layout({ active, children }: { active: string; children: ReactNode }) {
  const navigate = useNavigate()
  const { push } = useToast()
  const userId = tokenStore.getUserId()
  const ctx = useSyncExternalStore(storeContext.subscribe, storeContext.get)
  const taskCount = useTaskBadge()

  useEffect(() => {
    if (!userId) navigate('/login', { replace: true })
  }, [userId, navigate])

  // 身份初始化（幂等：同账号已就绪不重复请求，路由切换不重刷）
  useEffect(() => {
    if (userId) void storeContext.load()
  }, [userId])

  // 会话失效回调：清门店上下文→跳登录，文案区分停用/过期（push/navigate 均稳定引用）
  useEffect(() => {
    session.setExpiredHandler((reason) => {
      storeContext.reset()
      resetUnreadCount()
      resetTaskBadge()
      push(reason === 'disabled' ? '账号已被停用，请重新登录' : '登录已过期，请重新登录', 'error')
      navigate('/login', { replace: true })
    })
    return () => session.setExpiredHandler(null)
  }, [navigate, push])

  if (!userId) return null

  const logout = async () => {
    session.bump() // 让在途 refresh 结果作废，避免登出后被旧会话重建
    try {
      await authApi.logout()
    } catch {
      // 登出失败也清理本地会话
    }
    tokenStore.clear()
    resetUnreadCount() // 未读数按门店上下文缓存，不清会把上一个账号的红点带给下一个
    resetTaskBadge()
    storeContext.reset() // 清门店选择与内存身份，防旧账号门店残留串到新账号
    navigate('/login', { replace: true })
  }

  if (ctx.phase === 'idle' || ctx.phase === 'loading') return <BootScreen />
  if (ctx.phase === 'error') return <BootErrorScreen message={ctx.error} onRetry={() => void storeContext.load()} onLogout={() => void logout()} />
  if (ctx.phase === 'no-identity') return <NoIdentityScreen onLogout={() => void logout()} />

  const identity = ctx.identity!

  const onNav = (key: string, label: string) => {
    const to = ROUTED[key]
    if (to) navigate(to)
    else push(`${label}：模块将随后端段次上线，敬请期待`, 'info')
  }

  return (
    <div className="min-h-[100dvh] bg-blue-mist">
      <header className="sticky top-0 z-30 flex h-16 items-center justify-between gap-3 border-b border-line bg-white px-4 sm:px-6">
        <div className="flex min-w-0 items-center gap-3">
          <Logo className="h-9 w-9 shrink-0" />
          <p className="text-lg font-bold text-ink">宠物管家</p>
          <span className="mx-1 hidden h-5 w-px bg-line md:block" />
          <p className="hidden truncate text-sm text-muted md:block">让每一只宠物都被温柔以待</p>
        </div>
        <div className="flex shrink-0 items-center gap-3 sm:gap-5">
          {ctx.candidates.length > 1 && <StoreSwitcher state={ctx} />}
          <NotificationBell />
          <UserMenu
            name={identity.name || MANAGER.name}
            roles={ctx.candidates.find((s) => s.storeId === ctx.storeId)?.roles ?? (ctx.platformAdmin ? ['ADMIN'] : [])}
            platformAdmin={ctx.platformAdmin}
            onLogout={logout}
          />
        </div>
      </header>

      <div className="flex">
        <aside className="sticky top-16 hidden h-[calc(100dvh-4rem)] w-[224px] shrink-0 overflow-y-auto border-r border-line bg-white p-3 lg:block">
          <nav className="flex flex-col gap-1">
            {NAV_ITEMS.map(({ key, label, Icon }) => {
              const badge = key === 'tasks' ? taskBadgeText(taskCount) : null
              return (
                <button
                  key={key}
                  type="button"
                  onClick={() => onNav(key, label)}
                  aria-label={
                    key === 'tasks' && taskCount !== null && taskCount > 0
                      ? `我的任务，今日 ${taskCount} 个未完成任务`
                      : undefined
                  }
                  className={cx(
                    'flex h-11 items-center gap-3 rounded-xl px-4 text-sm transition',
                    key === active ? 'bg-blue-soft font-bold text-blue' : 'text-ink hover:bg-blue-mist',
                  )}
                >
                  <Icon className="h-[18px] w-[18px] shrink-0" />
                  {label}
                  {badge && (
                    <span className="ml-auto grid h-5 min-w-[20px] place-items-center rounded-full bg-[#E8506B] px-1 text-[10px] font-bold text-white">
                      {badge}
                    </span>
                  )}
                </button>
              )
            })}
          </nav>
        </aside>
        <div className="min-w-0 flex-1">{children}</div>
      </div>
    </div>
  )
}

/** 门店切换控件：候选集来自 storeContext.candidates（普通员工=归属店，平台管理员=全网 OPEN 店） */
function StoreSwitcher({ state }: { state: StoreCtxState }) {
  const [open, setOpen] = useState(false)
  const stores = state.candidates
  const current = stores.find((s) => s.storeId === state.storeId)
  return (
    <div className="relative">
      <button
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="flex h-9 items-center gap-1.5 rounded-full bg-blue-soft px-3 text-xs font-bold text-blue transition hover:bg-blue-mist"
      >
        <IconMapPin className="h-4 w-4 shrink-0" />
        <span className="max-w-[120px] truncate sm:max-w-[180px]">{current ? storeLabel(current) : '选择门店'}</span>
        {state.platformAdmin && <span className="shrink-0 rounded bg-blue px-1.5 py-0.5 text-[10px] font-bold text-white">平台代管</span>}
        <IconChevronDown className={cx('h-3.5 w-3.5 shrink-0 transition-transform', open && 'rotate-180')} />
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-40" aria-hidden="true" onClick={() => setOpen(false)} />
          <div role="menu" className="absolute right-0 top-full z-50 mt-2 w-56 rounded-xl bg-white p-1.5 shadow-card ring-1 ring-line">
            <p className="px-3 pb-1 pt-2 text-[11px] font-semibold text-muted">
              {state.platformAdmin ? '平台代管 · 选择要代入的门店' : '切换工作门店'}
            </p>
            {stores.map((s) => (
              <button
                key={s.storeId}
                type="button"
                role="menuitemradio"
                aria-checked={s.storeId === state.storeId}
                onClick={() => {
                  storeContext.switchTo(s.storeId)
                  setOpen(false)
                }}
                className={cx(
                  'flex w-full items-center justify-between gap-2 rounded-lg px-3 py-2 text-left text-sm transition hover:bg-blue-mist',
                  s.storeId === state.storeId ? 'font-bold text-blue' : 'text-ink',
                )}
              >
                <span className="min-w-0 truncate">{storeLabel(s)}</span>
                <span className="shrink-0 text-[11px] text-muted">{s.roles.join('/') || '员工'}</span>
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  )
}

function UserMenu({ name, roles, platformAdmin, onLogout }: { name: string; roles: string[]; platformAdmin: boolean; onLogout: () => void }) {
  const [open, setOpen] = useState(false)
  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-2.5 rounded-full py-1 pl-1 pr-2 transition hover:bg-blue-mist"
      >
        <span className="grid h-9 w-9 place-items-center rounded-full bg-blue-soft text-blue">
          <IconUser className="h-5 w-5" />
        </span>
        <span className="hidden text-left sm:block">
          <p className="text-sm font-bold leading-tight text-ink">{name}</p>
          {/* 平台管理员必须自证身份：他此刻看到的门店数据是"代入"来的，不是该店员工身份（ADR-044） */}
          <p className="text-xs leading-tight text-muted">{platformAdmin ? '平台管理员代管' : roles[0] || MANAGER.role}</p>
        </span>
        <IconChevronDown className="hidden h-4 w-4 text-muted sm:block" />
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-40" aria-hidden="true" onClick={() => setOpen(false)} />
          <div className="absolute right-0 top-full z-50 mt-2 w-40 rounded-xl bg-white p-1.5 shadow-card ring-1 ring-line">
            <button
              type="button"
              onClick={() => void onLogout()}
              className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-sm text-ink transition hover:bg-blue-mist"
            >
              <IconLogout className="h-4 w-4" /> 退出登录
            </button>
          </div>
        </>
      )}
    </div>
  )
}

/* ---- 初始化三屏：加载 / 可重试错误 / 无员工身份引导（非错误页，不跳登录） ---- */

function BootScreen() {
  return (
    <main className="grid min-h-[100dvh] place-items-center bg-brand-panel">
      <div className="flex flex-col items-center gap-4">
        <Logo className="h-12 w-12" />
        <span className="h-7 w-7 animate-spin rounded-full border-[3px] border-blue border-t-transparent" role="status" aria-label="加载中" />
        <p className="text-sm text-muted">正在校验员工身份与门店…</p>
      </div>
    </main>
  )
}

function BootErrorScreen({ message, onRetry, onLogout }: { message: string | null; onRetry: () => void; onLogout: () => void }) {
  return (
    <main className="grid min-h-[100dvh] place-items-center bg-brand-panel px-4">
      <div className="w-full max-w-sm rounded-3xl bg-white p-8 text-center shadow-card ring-1 ring-line">
        <Logo className="mx-auto h-11 w-11" />
        <h1 className="mt-5 text-lg font-bold text-ink">身份加载失败</h1>
        <p className="mt-2 text-sm text-muted">{message || '网络异常，请检查连接后重试'}</p>
        <button type="button" onClick={onRetry} className="mt-6 h-11 w-full rounded-xl bg-blue text-sm font-bold text-white transition hover:bg-blue-deep">
          重试
        </button>
        <button type="button" onClick={onLogout} className="mt-3 w-full text-xs text-muted underline-offset-2 hover:underline">
          退出登录
        </button>
      </div>
    </main>
  )
}

function NoIdentityScreen({ onLogout }: { onLogout: () => void }) {
  return (
    <main className="grid min-h-[100dvh] place-items-center bg-brand-panel px-4">
      <div className="w-full max-w-sm rounded-3xl bg-white p-8 text-center shadow-card ring-1 ring-line">
        <span className="mx-auto grid h-14 w-14 place-items-center rounded-full bg-blue-soft text-blue">
          <IconUser className="h-7 w-7" />
        </span>
        <h1 className="mt-5 text-lg font-bold text-ink">尚未开通员工身份</h1>
        <p className="mt-2 text-sm leading-relaxed text-muted">
          当前账号还没有绑定 B 端员工身份与门店归属。
          <br />
          请联系门店店长或平台管理员完成绑定后重新登录。
        </p>
        <button type="button" onClick={onLogout} className="mt-6 h-11 w-full rounded-xl border border-line text-sm font-semibold text-ink transition hover:border-blue hover:text-blue">
          退出登录
        </button>
      </div>
    </main>
  )
}
