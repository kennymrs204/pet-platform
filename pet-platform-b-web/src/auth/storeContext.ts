import { authApi } from '../api/auth'
import { adminApi } from '../api/admin'
import { BizError, type EmployeeIdentities, type StoreIdentity } from '../api/types'
import { tokenStore } from './tokenStore'

/**
 * 门店上下文（阶段 3 / 阶段 0 B1-B6 规则）：
 * - 身份与可选门店来自 GET /auth/employee-identities（服务端按登录人计算，前端只读）；
 * - 当前 storeId 仅是"用户选择的工作上下文"，不构成授权（越权由后端 10003 兜底）；
 * - 会话内持久化于 sessionStorage 'ppb.storeCtx'，恢复时校验仍 ∈ **候选门店集合**，
 *   不合法则回默认规则：isPrimary 优先 → storeId 数值升序最小；
 * - 平台管理员（roles 含 ADMIN）**可代入任意 OPEN 门店**（ADR-044 落地矩阵:54）：候选集改自
 *   `GET /admin/stores`，不再锁死总部虚拟店——旧行为让 Boss 打开 B 端只看到空店，权限反而比店长小；
 * - 账号切换/登出/会话失效必须 reset，防旧账号门店残留或被旧请求串用。
 */

const K_STORE = 'ppb.storeCtx'

export type StoreCtxPhase = 'idle' | 'loading' | 'ready' | 'no-identity' | 'error'

export interface StoreCtxState {
  phase: StoreCtxPhase
  identity: EmployeeIdentities | null
  storeId: string | null
  /**
   * 身份含 ADMIN 角色 = 平台管理员（ADR-044）。注意语义已变：
   * 旧含义是"锁死在总部店、禁切店"（结果 Boss 看到的永远是空店，比店长还小）；
   * 现在是"候选门店来自 `GET /admin/stores`，可代入任意 OPEN 门店"，界面据此标「平台代管」。
   */
  platformAdmin: boolean
  /**
   * 当前可切换/可恢复的门店集合：普通员工=自己的 ACTIVE 归属；平台管理员=全网 OPEN 店。
   * 一切"能不能切到这个店"的判定只看这一份，不再直接读 identity.stores（那会把平台候选漏掉）。
   */
  candidates: StoreIdentity[]
  error: string | null
}

let state: StoreCtxState = { phase: 'idle', identity: null, storeId: null, platformAdmin: false, candidates: [], error: null }
let inflight: Promise<void> | null = null
let loadedForUser: string | null = null
const listeners = new Set<() => void>()

function emit(patch: Partial<StoreCtxState>): void {
  state = { ...state, ...patch }
  listeners.forEach((l) => l())
}

function readStored(): string | null {
  try {
    return window.sessionStorage.getItem(K_STORE)
  } catch {
    return null
  }
}
function writeStored(v: string): void {
  try {
    window.sessionStorage.setItem(K_STORE, v)
  } catch {
    /* 隐私模式忽略，内存态仍可用 */
  }
}
function clearStored(): void {
  try {
    window.sessionStorage.removeItem(K_STORE)
  } catch {
    /* ignore */
  }
}

/** storeId 为字符串化雪花/小整数：先比长度再字典序 = 数值升序 */
function minStoreId(ids: string[]): string {
  return [...ids].sort((a, b) => a.length - b.length || (a < b ? -1 : 1))[0]
}

function pickDefault(stores: StoreIdentity[]): string {
  const primary = stores.find((s) => s.isPrimary)
  return (primary ?? stores.find((s) => s.storeId === minStoreId(stores.map((x) => x.storeId))))!.storeId
}

export const storeContext = {
  get(): StoreCtxState {
    return state
  },

  subscribe(listener: () => void): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },

  /** client 注入 X-Store-Id 的唯一取值口：未就绪或账号已切换 → null（调用方拒绝发请求） */
  currentStoreId(): string | null {
    if (state.phase !== 'ready') return null
    if (loadedForUser !== tokenStore.getUserId()) return null
    return state.storeId
  },

  /**
   * 幂等初始化：同账号 ready/no-identity 后不重复请求（路由切换不重刷）；
   * in-flight 共享；force=true 或账号变更时重新拉取。
   */
  load(force = false): Promise<void> {
    const uid = tokenStore.getUserId()
    if (!uid) return Promise.resolve()
    if (!force && loadedForUser === uid && (state.phase === 'ready' || state.phase === 'no-identity')) {
      return Promise.resolve()
    }
    if (inflight) return inflight
    emit({ phase: 'loading', error: null })
    inflight = (async () => {
      try {
        const data = await authApi.employeeIdentities()
        const stores = data?.stores ?? []
        loadedForUser = uid
        if (!data?.employeeId || stores.length === 0) {
          clearStored()
          emit({ phase: 'no-identity', identity: null, storeId: null, platformAdmin: false, candidates: [] })
          return
        }
        const platformAdmin = stores.some((s) => s.roles.includes('ADMIN'))
        // 平台管理员的候选店来自 /admin/stores（全网 OPEN 店），不是他那条总部店归属（ADR-044）。
        //
        // 门店锚点取**服务端 identities 返回的归属店**（`pickDefault(stores)`），不能取 sessionStorage：
        // 后者是用户可编辑的，而这一刻上下文还没建立、`currentStoreId()` 只会返回 null。
        // 旧代码在这里调无参的 `adminApi.stores()` ⇒ client 在 fetch 前抛 NO_STORE_CTX ⇒ 下面那个
        // 空 catch 把它和所有真错误一起吞掉 ⇒ 候选永远只剩总部店 ⇒ 界面上连门店切换器都不出现
        // （ADMIN 的代店入口被自己的加载顺序锁死，实测顶栏仍显示「平台代管」，最难从界面反推）。
        //
        // 拉不到仍然退回归属店：宁可少几个店可选，也不要把整个 B 端卡在加载失败上——但**不再静默**。
        let candidates = stores
        if (platformAdmin) {
          try {
            const all = await adminApi.stores(pickDefault(stores))
            const known = new Map(stores.map((s) => [s.storeId, s]))
            const platform: StoreIdentity[] = all.map((s) =>
              known.get(s.storeId) ?? { storeId: s.storeId, storeName: s.name, isPrimary: false, roles: ['ADMIN'] },
            )
            if (platform.length > 0) candidates = platform
          } catch (err) {
            // 保留可用回退，同时把原因留在控制台：这条失败过去完全没有痕迹，
            // 于是"权限没生效 / 门店头没带上 / 后端真不可达"三种病长得一模一样，无法归因。
            const reason = err instanceof BizError ? `${err.code} ${err.message}` : String(err)
            console.warn(`[storeContext] 平台候选门店拉取失败，退回归属店候选：${reason}`)
          }
        }
        const restored = readStored()
        const pick = restored && candidates.some((s) => s.storeId === restored) ? restored : pickDefault(candidates)
        writeStored(pick)
        emit({ phase: 'ready', identity: data, storeId: pick, platformAdmin, candidates })
      } catch (err) {
        // 10002 已由阶段 2 会话状态机处理（refresh 或清会话跳登录）；此处呈现可重试错误，
        // 绝不把网络/服务错误误判成"无员工身份"引导页。
        emit({
          phase: 'error',
          error: err instanceof BizError && err.message ? err.message : '网络异常，员工身份加载失败',
        })
      } finally {
        inflight = null
      }
    })()
    return inflight
  },

  switchTo(storeId: string): void {
    if (state.phase !== 'ready') return
    // 只允许切到当前候选集内的门店：普通员工=ACTIVE 归属，平台管理员=全网 OPEN 店。
    // 注意这**不构成授权**——服务端仍按 X-Store-Id + 权限码判（越权 10003），前端只是不给选。
    if (!state.candidates.some((s) => s.storeId === storeId)) return
    writeStored(storeId)
    emit({ storeId })
  },

  /** 登出/会话失效/切账号：清 sessionStorage 选择 + 内存身份，回到未初始化 */
  reset(): void {
    loadedForUser = null
    inflight = null
    clearStored()
    emit({ phase: 'idle', identity: null, storeId: null, platformAdmin: false, candidates: [], error: null })
  },
}

/** 门店显示名回退：storeName 为阶段 1 占位 null 时显示「门店 {id}」 */
export function storeLabel(s: StoreIdentity): string {
  const name = s.storeName?.trim()
  return name || `门店 ${s.storeId}`
}
