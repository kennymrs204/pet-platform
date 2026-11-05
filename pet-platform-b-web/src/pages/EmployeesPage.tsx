import { useEffect, useRef, useState, useSyncExternalStore, type FormEvent, type ReactNode } from 'react'
import Card from '../components/ui/Card'
import Drawer from '../components/ui/Drawer'
import Modal from '../components/ui/Modal'
import Layout from '../components/layout/Layout'
import {
  IconCalendar,
  IconCheckCircle,
  IconChevronRight,
  IconEdit,
  IconMoon,
  IconPlus,
  IconSearch,
  IconShield,
  IconUser,
} from '../components/ui/icons'
import { useToast } from '../components/ui/Toast'
import { storeContext } from '../auth/storeContext'
import { BizError } from '../api/types'
import {
  ADMIN_ONLY_ROLES,
  ALL_SKILL_CODES,
  EMPLOYEE_STATUS_LABEL,
  EMPLOYEE_TAB_LABEL,
  ROLE_LABEL,
  SCHEDULE_MAX_SPAN_DAYS,
  SKILL_LABEL,
  STORE_ASSIGNABLE_ROLES,
  WEEKDAY_LABEL,
  currentStoreAffiliationOf,
  employeeInitial,
  employeesApi,
  isCurrentStoreMember,
  isOnLeaveTodayInStore,
  isScheduleEligible,
  localDate,
  roleLabelsOf,
  resolveRoleSubmit,
  scheduleIneligibleReason,
  shiftText,
  skillsVisible,
  storeMembershipLabel,
  type BatchGenerateBody,
  type EmployeeGlobalStatus,
  type EmployeeManageResult,
  type EmployeeRow,
  type EmployeeShift,
  type EmployeeTab,
  type OnboardEmployeeBody,
} from '../api/employees'
import {
  getCandidates,
  loadCandidates,
  resetCandidates,
  subscribeCandidates,
  type CandidatesState,
} from '../lib/scheduleCandidates'
import { canManageSchedule, visibleEmpDetailTabs } from '../lib/schedulePermission'
import { cx } from '../lib/cx'
import { TONE } from '../lib/tone'
import type { Tone } from '../mock/dashboard'

const NAVY = 'bg-[#1E3A5F] text-white hover:bg-[#16304F]'
const TABS: EmployeeTab[] = ['ALL', 'ON_DUTY', 'LEAVE', 'IDLE', 'OFF']

/**
 * 员工管理页（ADR-024 读侧已接真实数据；ADR-033 写侧已接：纳编/授角色/授技能/移出本店）。
 * 字段严格照 emp_* DDL 与 ADR-024 三条硬约束（无手机号列/角色带门店维度/在班只由排班决定）。
 * 脚注④：只持 emp:view 的账号（前台）拿不到手机号、入职日期、技能与门店归属——这些字段是 null，
 * UI 显示「按权限不披露」而不是「没有值」。
 * 写侧授权（ADR-001-E）：店长可授 5 个员工型角色，STORE_MANAGER 只能由平台 ADMIN 授 → 该 chip 置灰并写明原因。
 */
export default function EmployeesPage() {
  const { push } = useToast()
  const ctx = useSyncExternalStore(storeContext.subscribe, storeContext.get)
  const storeId = ctx.storeId
  /**
   * 排班管理能力（schedule:manage 的前端等价判据，见 lib/schedulePermission）：取**当前门店**归属角色，
   * 而非任一门店——后端按 X-Store-Id 上下文判权，跨店时只有该店的 STORE_MANAGER 才算数。
   * 平台管理员（ADMIN）对任意 OPEN 代入店都并入 ADMIN 码集，故 platformAdmin 直接放行。
   */
  const currentStoreRoles = ctx.identity?.stores.find((s) => s.storeId === storeId)?.roles ?? []
  const canManage = canManageSchedule(ctx.platformAdmin === true, currentStoreRoles)

  const [tab, setTab] = useState<EmployeeTab>('ALL')
  const [keyword, setKeyword] = useState('')
  const kw = useDebounced(keyword, 300)
  /** 员工详情抽屉（列表只浏览，详情与全部操作在 Drawer 内完成）：null=关闭 */
  const [empId, setEmpId] = useState<string | null>(null)
  /** 页面级操作（新增员工 / 批量排班）走右侧抽屉承载——不内嵌、不撑开主页面（UI 重构） */
  const [drawer, setDrawer] = useState<{ kind: 'onboard' } | { kind: 'shift'; preset: EmployeeRow | null } | null>(null)
  const [busy, setBusy] = useState(false)

  const list = useAsync(() => employeesApi.list({ tab, keyword: kw || undefined }), `${storeId}|${tab}|${kw}`)

  const page = list.block.state === 'ready' ? list.block.data : null
  const rows = page ?? []
  /** 详情抽屉的数据源=最新列表行：写操作后 reload 列表，抽屉内容自动刷新 */
  const employee = empId ? rows.find((r) => r.employeeId === empId) ?? null : null

  const fail = (err: unknown) => push(err instanceof BizError ? err.message : '操作失败，请稍后重试', 'error')

  /** 写侧统一收口：成功回一份"这次到底改了什么"的回执，失败原样抛出（不做假成功） */
  const runWrite = async (call: () => Promise<EmployeeManageResult>, summarize: (r: EmployeeManageResult) => string) => {
    setBusy(true)
    try {
      const r = await call()
      push(summarize(r), 'success')
      setDrawer(null)
      list.reload()
    } catch (err) {
      fail(err)
    } finally {
      setBusy(false)
    }
  }

  /** 排班写侧的回执形状与员工写侧不同（BatchGenerateResult / void），所以另开一条收口，共用 busy 与失败提示。 */
  const runShiftWrite = async (call: () => Promise<string>) => {
    setBusy(true)
    try {
      push(await call(), 'success')
      setDrawer(null)
      list.reload()
    } catch (err) {
      fail(err)
    } finally {
      setBusy(false)
    }
  }

  /** 员工详情抽屉内的写操作收口：成功返回 true（调用方退回详情态），**不关抽屉**；失败已提示。 */
  const runWriteStay = async (call: () => Promise<EmployeeManageResult>, summarize: (r: EmployeeManageResult) => string): Promise<boolean> => {
    setBusy(true)
    try {
      const r = await call()
      push(summarize(r), 'success')
      list.reload()
      return true
    } catch (err) {
      fail(err)
      return false
    } finally {
      setBusy(false)
    }
  }

  return (
    <Layout active="employees">
      <div className="grid gap-5 p-4 sm:p-6 2xl:grid-cols-[minmax(0,1fr)_320px]">
        <div className="flex min-w-0 flex-col gap-5">
          <div className="rounded-2xl bg-white p-5 shadow-soft ring-1 ring-line sm:p-6">
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div>
                <h1 className="text-[26px] font-black text-ink">员工管理</h1>
                <p className="mt-1.5 text-sm text-muted">查看本店员工的角色、技能与排班（角色按门店独立授予）</p>
              </div>
              <div className="flex flex-wrap gap-2.5">
                <button type="button" onClick={() => setDrawer({ kind: 'shift', preset: null })} className="flex h-10 items-center gap-2 rounded-xl bg-white px-4 text-sm font-bold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue">
                  <IconCalendar className="h-4 w-4" /> 批量排班
                </button>
                <button type="button" onClick={() => setDrawer({ kind: 'onboard' })} className={cx('flex h-10 items-center gap-2 rounded-xl px-4 text-sm font-bold', NAVY)}>
                  <IconPlus className="h-4 w-4" /> 新增员工
                </button>
              </div>
            </div>

            <div className="mt-5 flex flex-wrap items-center gap-2.5">
              <div className="flex flex-wrap gap-2.5" role="tablist" aria-label="员工分类">
                {TABS.map((key) => (
                  <button
                    key={key}
                    type="button"
                    role="tab"
                    aria-selected={tab === key}
                    onClick={() => setTab(key)}
                    className={cx(
                      'h-9 rounded-full px-4 text-sm font-semibold transition',
                      tab === key ? 'bg-blue text-white shadow-soft' : 'bg-white text-ink ring-1 ring-line hover:text-blue',
                    )}
                  >
                    {EMPLOYEE_TAB_LABEL[key]}
                  </button>
                ))}
              </div>
              <label className="relative ml-auto w-full max-w-[260px]">
                <IconSearch className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
                <input
                  type="search"
                  value={keyword}
                  onChange={(e) => setKeyword(e.target.value)}
                  placeholder="搜姓名 / 工号 / 角色"
                  aria-label="搜索员工"
                  className="h-9 w-full rounded-full bg-white pl-9 pr-3 text-sm text-ink ring-1 ring-line placeholder:text-muted focus:outline-none focus:ring-blue"
                />
              </label>
            </div>

            <div className="mt-5 flex flex-col gap-3">
              {list.block.state === 'loading' && <ListLoading />}
              {list.block.state === 'error' && <ListError message={list.block.message} onRetry={list.reload} />}
              {page && rows.length === 0 && (
                <p className="rounded-2xl bg-blue-mist/60 py-12 text-center text-sm text-muted">
                  {kw ? `没有匹配「${kw}」的员工` : '当前门店没有符合条件的员工'}
                </p>
              )}
              {rows.map((e) => (
                <EmployeeBlock key={e.employeeId} employee={e} onOpen={() => setEmpId(e.employeeId)} />
              ))}
            </div>
          </div>
        </div>

        <div className="flex min-w-0 flex-col gap-5">
          <OverviewCard rows={rows} loading={list.block.state === 'loading'} />
          <SkillCoverageCard rows={rows} />
          <BoundaryCard />
        </div>
      </div>

      {/* 页面级操作抽屉：默认不渲染任何表单，主页面恒定以员工列表为主体 */}
      <OnboardDrawer
        open={drawer?.kind === 'onboard'}
        busy={busy}
        onCancel={() => setDrawer(null)}
        onSubmit={(body) => void runWrite(
          () => employeesApi.onboard(body),
          (r) => `${r.employeeCreated ? `已建档 ${r.name}` : `${r.name} 原已建档，本次只加入本店`}`
            + ` · 本店角色 ${r.roleCodes?.length ?? 0} 个${r.affiliationCreated ? ' · 新加入本店' : ''}`,
        )}
      />
      <ShiftDrawer
        open={drawer?.kind === 'shift'}
        busy={busy}
        storeId={storeId}
        preset={drawer?.kind === 'shift' ? drawer.preset : null}
        onCancel={() => setDrawer(null)}
        onSubmit={(body) => void runShiftWrite(async () => {
          const r = await employeesApi.generateShifts(body)
          // skipped 里的 storeId 是"已有那条班属于哪家店"，非本店的那部分才值得单列提醒
          const elsewhere = r.skipped.filter((s) => s.storeId && s.storeId !== storeId).length
          return r.created.length === 0
            ? `没有新增班次：${r.skipped.length} 个人日当天已有班，一律未改动`
            : `已排 ${r.created.length} 个班`
              + (r.skipped.length ? ` · ${r.skipped.length} 个人日已有班未改动${elsewhere ? `（其中 ${elsewhere} 个别店排的，去那家店调` : ''}` : '')
        })}
      />

      {/* 员工详情抽屉：列表只浏览，详情/角色/技能/资料/移出全部在抽屉内完成（点击行打开） */}
      {employee && (
        <EmployeeDrawer
          key={`${storeId}|${employee.employeeId}`}
          employee={employee}
          busy={busy}
          storeId={storeId}
          platformAdmin={ctx.platformAdmin === true}
          canManageSchedule={canManage}
          onClose={() => setEmpId(null)}
          onSaveRoles={(codes) => runWriteStay(
            () => {
              // 按操作者身份分流（resolveRoleSubmit）：平台管理员→平台端点（含 STORE_MANAGER，storeId 取当前操作上下文）；
              // 店长→门店端点（5 个员工型角色）。locked 态下保存按钮已禁用，此分支不会触发，故落门店端点也只是兜底。
              const target = resolveRoleSubmit(ctx.platformAdmin === true, storeId, employee.roleCodes)
              return target.kind === 'admin'
                ? employeesApi.adminAssignRoles(employee.employeeId, target.storeId, codes)
                : employeesApi.assignRoles(employee.employeeId, codes)
            },
            (r) => codes.length === 0
              ? `${r.name}：已撤销本店全部角色（权限即时生效，对方无需重新登录）`
              : `${r.name}：本店角色 = ${r.roleCodes?.map((c) => ROLE_LABEL[c] ?? c).join('、') ?? ''}`,
          )}
          onSaveSkills={(codes) => runWriteStay(
            // 平台管理员走平台通道（主店可能是无店长的总部虚拟店，店级路径会失败）
            () => ctx.platformAdmin
              ? employeesApi.adminUpdateSkills(employee.employeeId, codes)
              : employeesApi.assignSkills(employee.employeeId, codes),
            (r) => `技能已更新：${(r.skillCodes ?? []).map((c) => SKILL_LABEL[c] ?? c).join('、') || '（无）'}（全局属性，不分店）`,
          )}
          onSaveProfile={(body) => runWriteStay(
            () => employeesApi.updateProfile(employee.employeeId, body),
            (r) => `${r.name}：资料已更新（姓名 / 工号 / 入职日期对所有门店生效）`,
          )}
          onSaveStatus={(status) => runWriteStay(
            () => employeesApi.adminUpdateStatus(employee.employeeId, status),
            (r) => status === 'RESIGNED'
              ? `${r.name}：已办理离职`
              : status === 'SUSPENDED'
                ? `${r.name}：账号已停用（B 端权限即时失效）`
                : `${r.name}：账号已恢复为在职`,
          )}
          onArrangeShift={() => {
            setEmpId(null)
            // 只有可排班者才作为 preset 带入；不可排班者不带 preset（详情按钮已禁用，这里是防御性兜底），
            // 避免把已知无效员工塞进批次触发服务端整批拒绝。此路径是「从详情新开排班抽屉」，
            // 抽屉内尚无手工选定项，故不存在「取消 preset 却清掉用户已选合法员工」的问题。
            setDrawer({ kind: 'shift', preset: isScheduleEligible(employee, storeId) ? employee : null })
          }}
          onLeaveStore={() => runWriteStay(
            () => employeesApi.leaveStore(employee.employeeId),
            (r) => `${r.name}：已移出本店（他店归属不受影响）`,
          )}
          onDeleteShift={(scheduleId, workDate) => runShiftWrite(async () => {
            await employeesApi.deleteShift(scheduleId)
            return `已删除 ${employee.name} ${workDate} 的班次（该员工当天不再出现在可指派候选里）`
          })}
        />
      )}
    </Layout>
  )
}

/* ---- 区块数据（与首页/宠物页/客户页同模式：判别联合 + seq 丢弃陈旧响应 + 切店重拉） ---- */

type Block<T> = { state: 'loading' } | { state: 'error'; message: string } | { state: 'ready'; data: T }

function useAsync<T>(fetcher: () => Promise<T>, reloadKey: unknown): { block: Block<T>; reload: () => void } {
  const [block, setBlock] = useState<Block<T>>({ state: 'loading' })
  const [tick, setTick] = useState(0)
  const seq = useRef(0)
  const fetcherRef = useRef(fetcher)
  fetcherRef.current = fetcher

  useEffect(() => {
    if (reloadKey === null || reloadKey === undefined) return
    const my = ++seq.current
    setBlock({ state: 'loading' })
    fetcherRef.current().then(
      (data) => {
        if (my === seq.current) setBlock({ state: 'ready', data })
      },
      (err: unknown) => {
        if (my !== seq.current) return
        setBlock({ state: 'error', message: err instanceof BizError ? err.message : '加载失败，请重试' })
      },
    )
  }, [reloadKey, tick])

  return { block, reload: () => setTick((t) => t + 1) }
}

function useDebounced(value: string, ms: number): string {
  const [out, setOut] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setOut(value), ms)
    return () => clearTimeout(t)
  }, [value, ms])
  return out
}

/**
 * 批量排班候选：订阅独立加载器（`lib/scheduleCandidates`），与员工管理页当前可见列表分别管理。
 * 打开抽屉即按当前门店拉全店名册（ALL/空关键词）；关闭即作废在途请求并清空（不残留旧候选）；
 * 切店时 storeId 变化触发重拉，旧门店的晚到响应被加载器的 seq 守卫丢弃（不沿用另一门店候选）。
 */
function useScheduleCandidates(open: boolean, storeId: string | null): CandidatesState {
  const state = useSyncExternalStore(subscribeCandidates, getCandidates)
  useEffect(() => {
    if (open) void loadCandidates(storeId)
    else resetCandidates()
  }, [open, storeId])
  return state
}

/* ---- 员工行（紧凑：列表只浏览，点击打开员工详情抽屉） ---- */

function EmployeeBlock({ employee: e, onOpen }: { employee: EmployeeRow; onOpen: () => void }) {
  const duty = e.todayShift?.status
  const aff = currentStoreAffiliationOf(e)
  return (
    <div className="overflow-hidden rounded-xl bg-white ring-1 ring-line transition hover:ring-blue/30">
      <button
        type="button"
        aria-haspopup="dialog"
        aria-label={`查看 ${e.name} 的详情`}
        onClick={onOpen}
        className="flex w-full items-center gap-4 px-4 py-3.5 text-left transition hover:bg-blue-mist/40"
      >
        <Avatar name={e.name} id={e.employeeId} />
        <div className="min-w-0 flex-1">
          {/* 第一层：姓名（主信息）+ 在职状态/角色 badge；工号/手机号降权到第二层 */}
          <p className="flex flex-wrap items-center gap-2">
            <span className="truncate text-[15px] font-black text-ink">{e.name}</span>
            <Badge label={EMPLOYEE_STATUS_LABEL[e.status] ?? e.status} tone={statusTone(e)} />
            <Badge label={roleLabelsOf(e.roleCodes)} tone={e.roleCodes.length ? 'blue' : 'gray'} />
            {/* 全局状态与「本店归属」是两回事：已移出本店者全局仍可能 ACTIVE，这里单列一枚归属徽章 */}
            <Badge label={storeMembershipLabel(e)} tone={aff === 'ACTIVE' ? 'blue' : aff === 'UNKNOWN' ? 'orange' : 'gray'} />
          </p>
          {/* 第二层：降权元信息（同一行，小字灰色） */}
          <p className="mt-0.5 truncate text-xs text-muted">
            {e.phoneMasked ?? '—'} · 工号 {e.employeeNo ?? '—'} · 入职 {e.hiredAt ?? '—'} · 在职 {e.activeStoreCount ?? '—'} 家门店
          </p>
          {/* 第三层：技能 tag（更小更轻） */}
          {!!e.skillCodes?.length && (
            <p className="mt-1.5 flex flex-wrap gap-1.5">
              {e.skillCodes.map((c) => (
                <span key={c} className="rounded-md bg-blue-mist/70 px-1.5 py-0.5 text-[11px] text-muted">
                  {SKILL_LABEL[c] ?? c}
                </span>
              ))}
            </p>
          )}
        </div>
        {/* 右侧：排班状态 + 今日班次 + 详情入口箭头 */}
        <div className="hidden w-[120px] shrink-0 flex-col items-end gap-1 sm:flex">
          <Badge label={duty === 'WORKING' ? '在班' : duty === 'LEAVE' ? '请假' : '未排班'} tone={dutyTone(duty)} />
          <p className="text-[13px] font-bold leading-none text-ink">{shiftText(e.todayShift)}</p>
          <p className="text-[10px] leading-none text-muted/70">今日班次</p>
        </div>
        <IconChevronRight className="h-4 w-4 shrink-0 text-muted" />
      </button>
    </div>
  )
}

function statusTone(e: EmployeeRow): Tone {
  if (e.status === 'ACTIVE') return 'green'
  if (e.status === 'ON_LEAVE') return 'orange'
  return 'gray'
}
function dutyTone(duty: EmployeeShift['status'] | undefined): Tone {
  if (duty === 'WORKING') return 'blue'
  if (duty === 'LEAVE') return 'pink'
  return 'gray'
}

/* ---- 员工详情抽屉：概览 / 门店与角色 / 技能 / 排班 + 操作态（分配角色/技能/资料、移出本店） ---- */

type EmpTab = 'overview' | 'stores' | 'skills' | 'shifts'
type EmpMode = 'view' | 'roles' | 'skills' | 'profile'

const EMP_TABS: { key: EmpTab; label: string }[] = [
  { key: 'overview', label: '概览' },
  { key: 'stores', label: '门店与角色' },
  { key: 'skills', label: '技能' },
  { key: 'shifts', label: '排班' },
]

const TAB_ACTION = 'h-8 shrink-0 rounded-lg bg-white px-3 text-xs font-semibold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue'

function EmployeeDrawer({
  employee: e,
  busy,
  storeId,
  platformAdmin,
  canManageSchedule,
  onClose,
  onSaveRoles,
  onSaveSkills,
  onSaveProfile,
  onSaveStatus,
  onArrangeShift,
  onLeaveStore,
  onDeleteShift,
}: {
  employee: EmployeeRow
  busy: boolean
  storeId: string | null
  platformAdmin: boolean
  canManageSchedule: boolean
  onClose: () => void
  onSaveRoles: (codes: string[]) => Promise<boolean>
  onSaveSkills: (codes: string[]) => Promise<boolean>
  onSaveProfile: (body: { name: string; employeeNo?: string | null; hiredAt?: string | null }) => Promise<boolean>
  onSaveStatus: (status: EmployeeGlobalStatus) => Promise<boolean>
  onArrangeShift: () => void
  onLeaveStore: () => Promise<boolean>
  onDeleteShift: (scheduleId: string, workDate: string) => Promise<void>
}) {
  const { push } = useToast()
  const [mode, setMode] = useState<EmpMode>('view')
  const [tab, setTab] = useState<EmpTab>('overview')
  const [confirmLeave, setConfirmLeave] = useState(false)
  /** 停用/离职的二次确认（'' 不出现；恢复在职为非破坏动作，直接执行） */
  const [confirmStatus, setConfirmStatus] = useState<'SUSPENDED' | 'RESIGNED' | null>(null)

  /* 操作态草稿（进入即初始化；取消 = 退回详情，不提交） */
  const [rolesPicked, setRolesPicked] = useState<Set<string>>(new Set(e.roleCodes))
  const [skillsPicked, setSkillsPicked] = useState<Set<string>>(new Set(e.skillCodes ?? []))
  const [profile, setProfile] = useState({ name: e.name, no: e.employeeNo ?? '', hired: e.hiredAt ?? '' })

  const rolesDirty = JSON.stringify([...rolesPicked].sort()) !== JSON.stringify([...e.roleCodes].sort())
  const skillsDirty = JSON.stringify([...skillsPicked].sort()) !== JSON.stringify([...(e.skillCodes ?? [])].sort())
  const nameBad = !profile.name.trim() || profile.name.trim().length > 50
  const noBad = profile.no.length > 32
  /**
   * 角色写端点分流（与父层 onSaveRoles 同一判据 `resolveRoleSubmit`）：
   * `blocked` = 非平台操作者面对本店店长——店长端白名单不含 STORE_MANAGER，照常提交必被 10003 拒；
   * 若过滤掉 STORE_MANAGER 再提交，全量替换语义又会**误撤销**其店长角色。故整表锁定为只读、禁止提交，
   * 并说明「店长角色只能由平台管理员调整」，绝不发注定失败或有害的请求（ADR-001-E 防提权链）。
   */
  const roleTarget = resolveRoleSubmit(platformAdmin, storeId, e.roleCodes)
  const roleEditLocked = roleTarget.kind === 'blocked'
  const saveBlocked = busy
    || (mode === 'roles' && (!rolesDirty || roleEditLocked))
    || (mode === 'skills' && !skillsDirty)
    || (mode === 'profile' && (nameBad || noBad))

  /**
   * 排班资格（与批量候选同一判据 `scheduleIneligibleReason`）：不可排班时禁用「安排排班」入口并写明原因，
   * 绝不把已知无效员工作为 preset 带入批量排班。资格不可判定（归属被裁剪）时同样禁用，不凭旧状态臆断「可排班」。
   */
  const arrangeReason = scheduleIneligibleReason(e, storeId)

  /**
   * 员工详情可见 Tab：无排班管理权（schedule:manage）者隐藏「排班」Tab。
   * 隐藏即不挂载 ShiftsTab，从而不会以 `weeklyShifts(目标员工id)` 去查他人排班——
   * 后端对只持 self:view 者会强制返回**本人**排班，那会被错显示成目标员工的排班（本批修复的错配）。
   */
  const visibleTabs = visibleEmpDetailTabs(canManageSchedule)

  const back = () => setMode('view')
  const saveRoles = async () => {
    if (await onSaveRoles([...rolesPicked])) back()
  }
  const saveSkills = async () => {
    if (await onSaveSkills([...skillsPicked])) back()
  }
  const saveProfile = async () => {
    if (nameBad || noBad) return
    if (await onSaveProfile({ name: profile.name.trim(), employeeNo: profile.no.trim() || null, hiredAt: profile.hired || null })) back()
  }
  const doLeave = async () => {
    if (await onLeaveStore()) {
      setConfirmLeave(false)
      onClose()
    }
  }
  const doStatus = async (status: EmployeeGlobalStatus) => {
    if (await onSaveStatus(status)) setConfirmStatus(null)
  }
  /** 停用 / 离职 / 恢复是平台操作：店长点击给明确提示（与「编辑资料」同模式），不静默失败 */
  const gatePlatformOp = (action: () => void) => () => {
    if (platformAdmin) action()
    else push('停用 / 离职 / 恢复为平台操作，仅平台管理员可用', 'info')
  }

  return (
    <>
      <Drawer
        open
        title="员工详情"
        ariaLabel="员工详情"
        width="sm:max-w-[600px]"
        onClose={onClose}
        footer={
          mode === 'view' ? undefined : (
            <div className="flex items-center gap-2.5">
              <button type="button" onClick={back} className="h-10 rounded-xl bg-white px-5 text-sm font-semibold text-ink ring-1 ring-line transition hover:ring-blue">
                取消
              </button>
              <button
                type="submit"
                form={`em-emp-${mode}-form`}
                disabled={saveBlocked}
                className={cx('h-10 rounded-xl px-5 text-sm font-bold text-white transition', NAVY, saveBlocked && 'opacity-50')}
              >
                {busy ? '提交中…' : mode === 'profile' ? '保存资料' : '保存'}
              </button>
            </div>
          )
        }
        headerNode={
          <div className="flex items-start gap-3">
            <Avatar name={e.name} id={e.employeeId} />
            <div className="min-w-0">
              <p className="flex flex-wrap items-center gap-2">
                <span className="truncate text-lg font-black text-ink">{e.name}</span>
                <Badge label={EMPLOYEE_STATUS_LABEL[e.status] ?? e.status} tone={statusTone(e)} />
                <Badge label={roleLabelsOf(e.roleCodes)} tone={e.roleCodes.length ? 'blue' : 'gray'} />
              </p>
              <p className="mt-1 truncate text-xs text-muted">
                {e.phoneMasked ?? '—'} · 工号 {e.employeeNo ?? '—'} · 入职 {e.hiredAt ?? '—'} · 在职 {e.activeStoreCount ?? '—'} 家门店
              </p>
            </div>
          </div>
        }
      >
        {mode === 'view' ? (
          <>
            <div className="flex flex-wrap gap-1.5" role="tablist" aria-label="员工详情分类">
              {EMP_TABS.filter((t) => visibleTabs.includes(t.key)).map((t) => (
                <button
                  key={t.key}
                  type="button"
                  role="tab"
                  aria-selected={tab === t.key}
                  onClick={() => setTab(t.key)}
                  className={cx('h-8 rounded-lg px-3 text-[13px] font-semibold transition', tab === t.key ? 'bg-blue-soft text-blue' : 'text-muted hover:bg-blue-mist hover:text-ink')}
                >
                  {t.label}
                </button>
              ))}
            </div>

            {tab === 'overview' && (
              <div className="mt-4 flex flex-col gap-4">
                <div className="grid grid-cols-2 gap-x-6 gap-y-3">
                  <Field label="员工状态" value={EMPLOYEE_STATUS_LABEL[e.status] ?? e.status} />
                  <Field label="本店归属" value={storeMembershipLabel(e)} muted={currentStoreAffiliationOf(e) !== 'ACTIVE'} />
                  <Field label="手机号" value={e.phoneMasked ?? '—'} muted={!e.phoneMasked} />
                  <Field label="工号" value={e.employeeNo ?? '—'} muted={!e.employeeNo} />
                  <Field label="入职日期" value={e.hiredAt ?? '—'} muted={!e.hiredAt} />
                  <Field label="在职门店" value={e.activeStoreCount != null ? `${e.activeStoreCount} 家` : '—'} />
                </div>
                <div className="flex flex-wrap gap-2">
                  <ActionButton Icon={IconShield} label="分配角色" onClick={() => setMode('roles')} />
                  <ActionButton Icon={IconCheckCircle} label="分配技能" onClick={() => setMode('skills')} />
                  {/* 无排班管理权者不显示「安排排班」入口：既不能查他人排班，也不能把目标员工作为 preset 带入批量排班。
                      有权者仍按排班资格（arrangeReason）禁用不可排班的员工。 */}
                  {canManageSchedule && (
                    <ActionButton
                      Icon={IconCalendar}
                      label="安排排班"
                      onClick={onArrangeShift}
                      disabled={arrangeReason !== null}
                      title={arrangeReason ?? '为该员工安排排班'}
                    />
                  )}
                  <ActionButton
                    Icon={IconEdit}
                    label="编辑资料"
                    onClick={() =>
                      platformAdmin
                        ? setMode('profile')
                        : push('编辑资料仅平台管理员可用：姓名 / 工号 / 入职日期对所有门店生效', 'info')
                    }
                    title={platformAdmin ? '姓名 / 工号 / 入职日期为跨店共享字段，对所有门店生效' : '跨店共享字段，仅平台管理员可修改'}
                  />
                </div>
                <div className="border-t border-line pt-4">
                  <p className="text-xs font-bold text-ink">账号状态</p>
                  <p className="mt-1 text-[11px] leading-relaxed text-muted">
                    当前：{EMPLOYEE_STATUS_LABEL[e.status] ?? e.status}。停用 / 离职后 TA 将无法进入 B 端（权限与登录态即时失效）；
                    C 端顾客身份不受影响。请先确认 TA 名下已无进行中的工作。
                  </p>
                  <div className="mt-2 flex flex-wrap gap-2">
                    {(e.status === 'ACTIVE' || e.status === 'ON_LEAVE') && (
                      <button
                        type="button"
                        onClick={gatePlatformOp(() => setConfirmStatus('SUSPENDED'))}
                        className="h-9 rounded-lg bg-white px-4 text-xs font-bold text-[#8A6100] ring-1 ring-[#F2DFB0] transition hover:bg-[#FFF8EC]"
                      >
                        停用账号
                      </button>
                    )}
                    {e.status !== 'ACTIVE' && (
                      <button
                        type="button"
                        disabled={busy}
                        onClick={gatePlatformOp(() => void doStatus('ACTIVE'))}
                        className={cx('h-9 rounded-lg px-4 text-xs font-bold text-white transition', NAVY, busy && 'opacity-50')}
                      >
                        恢复在职
                      </button>
                    )}
                    {e.status !== 'RESIGNED' && (
                      <button
                        type="button"
                        onClick={gatePlatformOp(() => setConfirmStatus('RESIGNED'))}
                        className="h-9 rounded-lg bg-white px-4 text-xs font-bold text-[#C63B57] ring-1 ring-[#F2C9D2] transition hover:bg-[#FDF0F3]"
                      >
                        办理离职
                      </button>
                    )}
                  </div>
                </div>
                {e.status !== 'RESIGNED' && (
                  <div className="border-t border-line pt-4">
                    <p className="text-xs font-bold text-[#C63B57]">门店关系</p>
                    <p className="mt-1 text-[11px] leading-relaxed text-muted">移出后 TA 在本店的归属标记为「已结束」；他店归属不受影响。</p>
                    <button
                      type="button"
                      onClick={() => setConfirmLeave(true)}
                      className="mt-2 h-9 rounded-lg bg-white px-4 text-xs font-bold text-[#C63B57] ring-1 ring-[#F2C9D2] transition hover:bg-[#FDF0F3]"
                    >
                      移出本店
                    </button>
                  </div>
                )}
              </div>
            )}

            {tab === 'stores' && (
              <div className="mt-4 flex flex-col gap-3">
                <div className="flex items-center justify-between gap-3">
                  <p className="text-xs font-semibold text-ink">
                    门店与角色 <span className="font-normal text-muted">（角色按门店独立授予）</span>
                  </p>
                  <button type="button" onClick={() => setMode('roles')} className={TAB_ACTION}>
                    分配角色
                  </button>
                </div>
                {!e.affiliations ? (
                  <p className="rounded-xl bg-blue-mist/60 px-3 py-3 text-xs text-muted">暂无可查看的门店与角色信息</p>
                ) : e.affiliations.length === 0 ? (
                  <p className="rounded-xl bg-blue-mist/60 px-3 py-3 text-xs text-muted">该员工暂无门店归属记录</p>
                ) : (
                  <ul className="divide-y divide-line overflow-hidden rounded-xl ring-1 ring-line">
                    {e.affiliations.map((a) => (
                      <li key={`${a.storeId}-${a.status}`} className="flex flex-wrap items-center gap-3 bg-white px-3 py-3">
                        <span className="min-w-[120px] flex-1 truncate text-sm font-bold text-ink">{storeText(a)}</span>
                        {a.isPrimary && <Badge label="主店" tone="blue" />}
                        <Badge label={a.status === 'ACTIVE' ? '在职归属' : '已结束'} tone={a.status === 'ACTIVE' ? 'green' : 'gray'} />
                        <span className="shrink-0 text-xs text-muted">{roleLabelsOf(a.roleCodes)}</span>
                        <span className="shrink-0 font-mono text-[11px] text-muted">{a.startedAt ?? '—'}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )}

            {tab === 'skills' && (
              <div className="mt-4 flex flex-col gap-3">
                <div className="flex items-center justify-between gap-3">
                  <p className="text-xs font-semibold text-ink">技能</p>
                  <button type="button" onClick={() => setMode('skills')} className={TAB_ACTION}>
                    分配技能
                  </button>
                </div>
                {!e.skillCodes ? (
                  <p className="rounded-xl bg-blue-mist/60 px-3 py-3 text-xs text-muted">暂无可查看的技能信息</p>
                ) : e.skillCodes.length === 0 ? (
                  <p className="rounded-xl bg-blue-mist/60 px-3 py-3 text-xs text-muted">尚未分配技能</p>
                ) : (
                  <div className="flex flex-wrap gap-2">
                    {e.skillCodes.map((c) => (
                      <span key={c} className="rounded-lg bg-blue-mist/70 px-2.5 py-1 text-xs font-semibold text-ink">
                        {SKILL_LABEL[c] ?? c}
                      </span>
                    ))}
                  </div>
                )}
              </div>
            )}

            {canManageSchedule && tab === 'shifts' && <ShiftsTab employee={e} arrangeReason={arrangeReason} onArrange={onArrangeShift} onDelete={onDeleteShift} />}
          </>
        ) : (
          <div className="flex flex-col gap-4">
            <button type="button" onClick={back} className="flex w-fit items-center gap-1 text-xs font-semibold text-muted transition hover:text-blue">
              ← 返回员工详情
            </button>
            <div>
              <p className="text-base font-black text-ink">{mode === 'roles' ? '分配角色' : mode === 'skills' ? '分配技能' : '编辑员工资料'}</p>
              <p className="mt-0.5 text-xs text-muted">当前员工：{e.name}</p>
            </div>

            {mode === 'roles' && (
              roleEditLocked ? (
                <div className="flex flex-col gap-3">
                  <p className="rounded-xl bg-[#FFF8EC] px-3 py-2 text-[11px] leading-relaxed text-[#8A6100] ring-1 ring-[#F2DFB0]">
                    该员工是本店店长。<span className="font-bold">店长角色只能由平台管理员调整</span>；
                    为避免在全量替换时误撤销本店店长（且店长端接口不接受 STORE_MANAGER），这里不可编辑其角色集合。
                  </p>
                  <p className="text-xs font-semibold text-ink">当前本店角色（只读）</p>
                  <div className="flex flex-wrap gap-2">
                    {e.roleCodes.length === 0 ? (
                      <span className="text-xs text-muted">未分配角色</span>
                    ) : (
                      e.roleCodes.map((c) => (
                        <span
                          key={c}
                          className={cx(
                            'rounded-lg px-2.5 py-1 text-xs font-semibold',
                            c === 'STORE_MANAGER' ? 'bg-blue-soft text-blue' : 'bg-blue-mist/70 text-ink',
                          )}
                        >
                          {ROLE_LABEL[c] ?? c}
                        </span>
                      ))
                    )}
                  </div>
                </div>
              ) : (
                <form id="em-emp-roles-form" onSubmit={(ev) => { ev.preventDefault(); void saveRoles() }} className="flex flex-col gap-3">
                  <p className="text-xs font-semibold text-ink">
                    本店角色 <span className="font-normal text-muted">（可多选 = 兼岗）</span>
                  </p>
                  <OptionCards
                    options={STORE_ASSIGNABLE_ROLES.map((c) => ({ code: c, label: ROLE_LABEL[c] ?? c }))}
                    locked={platformAdmin ? [] : ADMIN_ONLY_ROLES.map((c) => ({ code: c, label: ROLE_LABEL[c] ?? c }))}
                    picked={rolesPicked}
                    onToggle={(code) => toggleIn(setRolesPicked, code)}
                  />
                  {platformAdmin && (
                    <>
                      <p className="mt-1 text-xs font-semibold text-ink">
                        店长角色 <span className="font-normal text-muted">（平台管理员可任命 / 撤销；撤销本店最后一名店长会被服务端拒绝）</span>
                      </p>
                      <OptionCards
                        options={ADMIN_ONLY_ROLES.map((c) => ({ code: c, label: ROLE_LABEL[c] ?? c }))}
                        locked={[]}
                        picked={rolesPicked}
                        onToggle={(code) => toggleIn(setRolesPicked, code)}
                      />
                    </>
                  )}
                  <p className="text-[11px] leading-relaxed text-muted">提交即以此处勾选为准；全部取消勾选等于撤销该员工在本店的全部角色。</p>
                  {!rolesDirty && <p className="text-[11px] text-muted">未做变更</p>}
                </form>
              )
            )}

            {mode === 'skills' && (
              <form id="em-emp-skills-form" onSubmit={(ev) => { ev.preventDefault(); void saveSkills() }} className="flex flex-col gap-3">
                <p className="text-xs font-semibold text-ink">
                  技能 <span className="font-normal text-muted">（可多选）</span>
                </p>
                {skillsOwnedElsewhere(e, storeId) && (
                  <p className="rounded-xl bg-[#FFF8EC] px-3 py-2 text-[11px] leading-relaxed text-[#8A6100] ring-1 ring-[#F2DFB0]">
                    {platformAdmin
                      ? `技能为跨店共享属性；TA 的主店是「${primaryStoreText(e)}」，你是平台管理员，可直接调整。`
                      : `技能为跨店共享属性，由 TA 的主店「${primaryStoreText(e)}」维护；本店提交可能被拒绝。`}
                  </p>
                )}
                {e.skillCodes === null && (
                  <p className="rounded-xl bg-[#FFF8EC] px-3 py-2 text-[11px] leading-relaxed text-[#8A6100] ring-1 ring-[#F2DFB0]">
                    当前账号暂不可查看该员工已有技能；本次勾选将作为提交后的全部技能。
                  </p>
                )}
                <OptionCards
                  options={ALL_SKILL_CODES.map((c) => ({ code: c, label: SKILL_LABEL[c] ?? c }))}
                  locked={[]}
                  picked={skillsPicked}
                  onToggle={(code) => toggleIn(setSkillsPicked, code)}
                />
                <p className="text-[11px] leading-relaxed text-muted">技能决定员工能接哪些任务；提交为全量替换。</p>
                {!skillsDirty && <p className="text-[11px] text-muted">未做变更</p>}
              </form>
            )}

            {mode === 'profile' && (
              <form id="em-emp-profile-form" onSubmit={(ev) => { ev.preventDefault(); void saveProfile() }} className="flex flex-col gap-3">
                <div className="grid gap-3 sm:grid-cols-2">
                  <label className="flex flex-col gap-1 text-xs text-muted">
                    姓名（必填，≤50 字）
                    <input value={profile.name} onChange={(ev) => setProfile({ ...profile, name: ev.target.value })} maxLength={50} className={FIELD} />
                  </label>
                  <label className="flex flex-col gap-1 text-xs text-muted">
                    工号（留空=清空，≤32 字）
                    <input value={profile.no} onChange={(ev) => setProfile({ ...profile, no: ev.target.value })} maxLength={32} placeholder="如 D005" className={FIELD} />
                  </label>
                  <label className="flex flex-col gap-1 text-xs text-muted">
                    入职日期（留空=清空）
                    <input type="date" value={profile.hired} onChange={(ev) => setProfile({ ...profile, hired: ev.target.value })} className={FIELD} />
                  </label>
                </div>
                <p className="text-[11px] leading-relaxed text-muted">
                  {nameBad ? '姓名必填且不超过 50 字' : noBad ? '工号不超过 32 字' : '姓名 / 工号 / 入职日期对所有门店生效；手机号与在职状态不可编辑。'}
                </p>
              </form>
            )}
          </div>
        )}
      </Drawer>

      {/* 移出本店 = 危险操作：二次确认 */}
      <Modal
        open={confirmLeave}
        title="移出本店"
        width="max-w-[420px]"
        onClose={() => setConfirmLeave(false)}
        footer={
          <div className="flex items-center gap-2.5">
            <button type="button" onClick={() => setConfirmLeave(false)} className="h-10 rounded-xl bg-white px-5 text-sm font-semibold text-ink ring-1 ring-line transition hover:ring-blue">
              取消
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() => void doLeave()}
              className="h-10 rounded-xl bg-[#C63B57] px-5 text-sm font-bold text-white transition hover:bg-[#A82F47] disabled:opacity-50"
            >
              {busy ? '提交中…' : '确认移出'}
            </button>
          </div>
        }
      >
        <p className="text-sm font-semibold text-ink">确定要将该员工移出当前门店吗？</p>
        <p className="mt-1.5 text-xs leading-relaxed text-muted">
          移出后 TA 在本店的归属标记为「已结束」；他店归属与角色不受影响。若当前门店是 TA 的主店，主店会自动转移给其他在职门店。
        </p>
      </Modal>

      {/* 停用 / 离职 = 平台级冻结动作：二次确认（恢复在职为非破坏动作，直接执行） */}
      <Modal
        open={confirmStatus !== null}
        title={confirmStatus === 'RESIGNED' ? '办理离职' : '停用账号'}
        width="max-w-[440px]"
        onClose={() => setConfirmStatus(null)}
        footer={
          <div className="flex items-center gap-2.5">
            <button type="button" onClick={() => setConfirmStatus(null)} className="h-10 rounded-xl bg-white px-5 text-sm font-semibold text-ink ring-1 ring-line transition hover:ring-blue">
              取消
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() => confirmStatus && void doStatus(confirmStatus)}
              className={cx(
                'h-10 rounded-xl px-5 text-sm font-bold text-white transition disabled:opacity-50',
                confirmStatus === 'RESIGNED' ? 'bg-[#C63B57] hover:bg-[#A82F47]' : 'bg-[#B7791F] hover:bg-[#9C6515]',
              )}
            >
              {busy ? '提交中…' : confirmStatus === 'RESIGNED' ? '确认离职' : '确认停用'}
            </button>
          </div>
        }
      >
        {confirmStatus === 'RESIGNED' ? (
          <>
            <p className="text-sm font-semibold text-ink">确定要办理离职吗？</p>
            <p className="mt-1.5 text-xs leading-relaxed text-muted">
              离职后 TA 将无法进入 B 端，且不能被任何门店再次纳编；如需返聘，由平台管理员恢复在职。
            </p>
          </>
        ) : (
          <>
            <p className="text-sm font-semibold text-ink">确定要停用该账号吗？</p>
            <p className="mt-1.5 text-xs leading-relaxed text-muted">
              停用后 TA 将无法进入 B 端（权限即时失效、登录态下线）；C 端顾客身份不受影响，可随时恢复在职。
            </p>
          </>
        )}
      </Modal>
    </>
  )
}

/* ---- 排班 Tab（懒加载：进入该 Tab 才拉未来 7 天） ---- */

function ShiftsTab({ employee: e, arrangeReason, onArrange, onDelete }: {
  employee: EmployeeRow
  arrangeReason: string | null
  onArrange: () => void
  onDelete: (scheduleId: string, workDate: string) => Promise<void>
}) {
  const week = useAsync(() => employeesApi.weeklyShifts(e.employeeId), e.employeeId)
  const WD = ['日', '一', '二', '三', '四', '五', '六']
  const days = Array.from({ length: 7 }, (_, i) => localDate(i))
  const byDate = new Map((week.block.state === 'ready' ? week.block.data : []).map((s) => [s.workDate.slice(0, 10), s]))
  return (
    <div className="mt-4 flex flex-col gap-3">
      <div className="flex items-center justify-between gap-3">
        <p className="text-xs font-semibold text-ink">未来 7 天排班</p>
        <button
          type="button"
          onClick={onArrange}
          disabled={arrangeReason !== null}
          title={arrangeReason ?? undefined}
          className={cx(TAB_ACTION, arrangeReason !== null && 'cursor-not-allowed opacity-50 hover:text-ink hover:ring-line')}
        >
          安排排班
        </button>
      </div>
      {week.block.state === 'loading' && (
        <p className="py-4 text-center text-xs text-muted">
          <Spinner /> 加载中…
        </p>
      )}
      {week.block.state === 'error' && <p className="py-4 text-center text-xs text-[#E8506B]">排班加载失败：{week.block.message}</p>}
      {week.block.state === 'ready' &&
        (week.block.data.length === 0 ? (
          <p className="rounded-xl bg-blue-mist/60 px-3 py-3 text-xs text-muted">未来 7 天暂无排班</p>
        ) : (
          <ul className="divide-y divide-line overflow-hidden rounded-xl ring-1 ring-line">
            {days.map((d) => {
              const s = byDate.get(d)
              const wd = WD[new Date(`${d}T00:00:00`).getDay()]
              return (
                <li key={d} className="flex items-center gap-3 bg-white px-3 py-2.5">
                  <span className="w-[92px] shrink-0 font-mono text-xs text-muted">
                    {d.slice(5).replace('-', '/')} 周{wd}
                  </span>
                  {s ? (
                    <>
                      <Badge label={s.status === 'WORKING' ? '在班' : '请假'} tone={dutyTone(s.status)} />
                      <span className="text-[13px] font-bold text-ink">{shiftText(s)}</span>
                      <button
                        type="button"
                        aria-label={`删除 ${d} 的班次`}
                        title="删除这天班次；该员工当天若还有进行中的预约，系统会拒绝并说明原因"
                        onClick={() => void onDelete(s.scheduleId, d).then(() => week.reload())}
                        className="ml-auto grid h-6 w-6 shrink-0 place-items-center rounded-full text-[13px] font-bold text-[#C63B57] ring-1 ring-line transition hover:bg-[#FDF0F3]"
                      >
                        ×
                      </button>
                    </>
                  ) : (
                    <span className="text-xs text-muted">未排班</span>
                  )}
                </li>
              )
            })}
          </ul>
        ))}
    </div>
  )
}

function storeText(a: { storeId: string; storeName: string | null } | undefined): string {
  if (!a) return '—'
  return a.storeName?.trim() || `门店 ${a.storeId}`
}

/** 主店（ACTIVE 归属里 is_primary 那个）。ADR-033 §4 保证移出主店时会确定性转移，所以它跟着人走。 */
function primaryAffiliation(e: EmployeeRow) {
  return e.affiliations?.find((a) => a.isPrimary && a.status === 'ACTIVE') ?? null
}

/**
 * 技能写权归主店（ADR-040）。归属读不到（脚注④不披露）时返回 false——
 * 那种账号本来也没有 emp:skill:assign，不该由前端再猜一层。
 */
function skillsOwnedElsewhere(e: EmployeeRow, storeId: string | null): boolean {
  if (!e.affiliations || storeId === null) return false
  const primary = primaryAffiliation(e)
  return primary !== null && primary.storeId !== storeId
}

function primaryStoreText(e: EmployeeRow): string {
  return storeText(primaryAffiliation(e) ?? undefined)
}

/* ---- 右栏 ---- */

function OverviewCard({ rows, loading }: { rows: EmployeeRow[]; loading: boolean }) {
  // 在职/在岗/未排班统计只算"本店在册在职"（全局 ACTIVE ∧ 本店归属 ACTIVE，见 isCurrentStoreMember）；
  // 「今日请假」另用 isOnLeaveTodayInStore，好让请假中(ON_LEAVE)的本店成员不被这条严格资格误排除。
  const active = rows.filter(isCurrentStoreMember)
  const boxes = [
    { key: 'all', label: '当前结果', value: rows.length, Icon: IconUser },
    { key: 'duty', label: '今日在班', value: active.filter((r) => r.todayShift?.status === 'WORKING').length, Icon: IconCheckCircle },
    { key: 'leave', label: '今日请假', value: rows.filter(isOnLeaveTodayInStore).length, Icon: IconMoon },
    { key: 'idle', label: '未排班', value: active.filter((r) => !r.todayShift).length, Icon: IconCalendar },
  ]
  const roles = tally(rows, (r) => r.roleCodes)
  return (
    <Card title="在岗概览">
      {loading ? (
        <p className="py-6 text-center text-xs text-muted">
          <Spinner /> 加载中…
        </p>
      ) : (
        <>
          <div className="grid grid-cols-2 gap-2">
            {boxes.map(({ key, label, value, Icon }) => (
              <div key={key} className="flex items-center gap-2.5 rounded-xl bg-blue-mist/60 px-3 py-2.5 ring-1 ring-line">
                <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-white text-blue">
                  <Icon className="h-4 w-4" />
                </span>
                <span className="min-w-0">
                  <p className="text-lg font-black leading-none text-ink">{value}</p>
                  <p className="text-[11px] text-muted">{label}</p>
                </span>
              </div>
            ))}
          </div>
          <p className="mt-4 mb-2 text-xs font-bold text-muted">角色分布</p>
          {roles.length === 0 ? (
            <p className="text-xs text-muted">当前列表没有已分配角色的员工</p>
          ) : (
            <ul className="flex flex-col gap-2">
              {roles.map(({ label, count }) => (
                <li key={label} className="flex items-center gap-2">
                  <span className="w-[76px] shrink-0 truncate text-[11px] text-ink">{label}</span>
                  <span className="h-2 flex-1 overflow-hidden rounded-full bg-blue-mist">
                    <span className="block h-full rounded-full bg-blue" style={{ width: `${Math.min(100, (count / Math.max(1, rows.length)) * 100)}%` }} />
                  </span>
                  <span className="w-6 shrink-0 text-right text-[11px] font-bold text-ink">{count}</span>
                </li>
              ))}
            </ul>
          )}
          <p className="mt-3 text-[11px] leading-relaxed text-muted">
            以上数字仅反映当前筛选结果；全店汇总暂未提供。
          </p>
        </>
      )}
    </Card>
  )
}

function SkillCoverageCard({ rows }: { rows: EmployeeRow[] }) {
  // 只统计"本店在册"成员；skillCodes 为 null 是"无权限"而非"无技能"，不能当空数组算成无人可服务。
  const members = rows.filter(isCurrentStoreMember)
  const skillsHidden = rows.length > 0 && rows.every((r) => !skillsVisible(r))
  return (
    <Card title="技能覆盖">
      {skillsHidden ? (
        <p className="py-4 text-xs leading-relaxed text-muted">
          当前账号没有技能明细查看权限，无法统计技能覆盖；如需查看，请联系店长授予员工编辑权限。
        </p>
      ) : (
        <ul className="flex flex-col gap-2">
          {ALL_SKILL_CODES.map((code) => {
            const n = members.filter((r) => (r.skillCodes ?? []).includes(code)).length
            return (
              <li key={code} className="flex items-center justify-between gap-2 text-[13px]">
                <span className="min-w-0 truncate text-ink">{SKILL_LABEL[code]}</span>
                <span
                  className="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold"
                  style={n === 0 ? { background: TONE.pink.bg, color: TONE.pink.fg } : n === 1 ? { background: TONE.orange.bg, color: TONE.orange.fg } : { background: TONE.green.bg, color: TONE.green.fg }}
                >
                  {n === 0 ? '本店无人可服务' : n === 1 ? '本店仅 1 人' : `本店 ${n} 人`}
                </span>
              </li>
            )
          })}
        </ul>
      )}
      <p className="mt-3 text-[11px] leading-relaxed text-muted">
        按当前列表统计本店在册员工（已移出本店 / 停用 / 离职者不计入）的技能覆盖；无技能明细权限时整块降级为不可统计。
      </p>
    </Card>
  )
}

function BoundaryCard() {
  return (
    <Card title="操作须知">
      <ul className="flex flex-col gap-2.5 text-[13px]">
        {[
          ['新增员工', '手机号需为已注册的 B 端账号；店长不能替他人设密码。'],
          ['门店店长角色', '仅平台管理员可授予。'],
          ['最后一名店长', '不可被移出本店，也不可撤销其店长身份。'],
          ['技能调整', '技能为跨店共享，由员工的主店维护。'],
          ['编辑资料', '姓名 / 工号 / 入职日期对所有门店生效，仅平台管理员可修改。'],
          ['请假', '暂不支持申请与审批；如需休息，可在排班中移除对应班次。'],
          ['员工头像', '暂不支持上传，当前以姓名首字母展示。'],
        ].map(([label, why]) => (
          <li key={label} className="flex items-start gap-2">
            <span className="mt-1 h-1.5 w-1.5 shrink-0 rotate-45 bg-[#2FA96B]" />
            <span className="min-w-0 flex-1">
              <span className="font-semibold text-ink">{label}</span>
              <span className="block text-[11px] text-muted">{why}</span>
            </span>
          </li>
        ))}
      </ul>
    </Card>
  )
}

function tally<T>(items: T[], pick: (t: T) => string[]): { label: string; count: number }[] {
  const m = new Map<string, number>()
  items.forEach((it) => pick(it).forEach((code) => m.set(code, (m.get(code) ?? 0) + 1)))
  return [...m.entries()].map(([code, count]) => ({ label: ROLE_LABEL[code] ?? code, count })).sort((a, b) => b.count - a.count)
}

/* ---- 通用小件 ---- */

const AVATAR_TONES: Tone[] = ['blue', 'green', 'purple', 'orange', 'pink']

function Avatar({ name, id }: { name: string; id: string }) {
  const tone = AVATAR_TONES[Number(BigInt(id) % BigInt(AVATAR_TONES.length))]
  return (
    <span className="grid h-12 w-12 shrink-0 place-items-center rounded-full text-base font-black" style={{ background: TONE[tone].bg, color: TONE[tone].fg }}>
      {employeeInitial(name)}
    </span>
  )
}

function Badge({ label, tone }: { label: string; tone: Tone }) {
  return (
    <span className="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold" style={{ background: TONE[tone].bg, color: TONE[tone].fg }}>
      {label}
    </span>
  )
}

function Field({ label, value, muted }: { label: string; value: string; muted?: boolean }) {
  return (
    <div>
      <p className="text-[11px] text-muted">{label}</p>
      <p className={cx('mt-0.5 text-sm font-semibold', muted ? 'text-muted' : 'text-ink')}>{value}</p>
    </div>
  )
}

function ActionButton({ Icon, label, onClick, tone = 'default', title, disabled = false }: { Icon: typeof IconEdit; label: string; onClick: () => void; tone?: 'default' | 'danger'; title?: string; disabled?: boolean }) {
  return (
    <button
      type="button"
      onClick={onClick}
      title={title}
      disabled={disabled}
      className={cx(
        'flex h-9 items-center gap-1.5 rounded-lg bg-white px-3.5 text-xs font-semibold ring-1 transition',
        tone === 'danger' ? 'text-[#C63B57] ring-[#F2C9D2] hover:bg-[#FDF0F3]' : 'text-ink ring-line hover:text-blue hover:ring-blue',
        disabled && 'cursor-not-allowed opacity-50 hover:text-ink hover:ring-line',
      )}
    >
      <Icon className="h-4 w-4" /> {label}
    </button>
  )
}

/* ---- 写侧表单 ---- */

const FIELD = 'h-10 w-full rounded-lg bg-white px-3 text-sm text-ink outline-none ring-1 ring-line transition placeholder:text-muted focus:ring-blue'

/** 卡片式多选（新增员工角色 / 详情抽屉内角色·技能共用）：选中=蓝底蓝边+实心圆点，不再横向堆叠 checkbox。 */
function OptionCards({ options, locked, picked, onToggle }: {
  options: { code: string; label: string }[]
  locked: { code: string; label: string }[]
  picked: Set<string>
  onToggle: (code: string) => void
}) {
  return (
    <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
      {options.map((o) => {
        const on = picked.has(o.code)
        return (
          <label
            key={o.code}
            className={cx(
              'flex cursor-pointer items-center gap-2.5 rounded-xl px-3 py-2.5 text-sm ring-1 transition',
              on ? 'bg-blue-soft font-bold text-blue ring-blue' : 'bg-white font-semibold text-ink ring-line hover:ring-blue/40',
            )}
          >
            <span className={cx('grid h-[18px] w-[18px] shrink-0 place-items-center rounded-full ring-1 transition', on ? 'bg-blue ring-blue' : 'bg-white ring-line')}>
              <span className={cx('h-2 w-2 rounded-full bg-white transition', !on && 'opacity-0')} />
            </span>
            <input type="checkbox" className="sr-only" checked={on} onChange={() => onToggle(o.code)} />
            {o.label}
          </label>
        )
      })}
      {locked.map((o) => (
        <span
          key={o.code}
          title="仅平台管理员可授予"
          className="flex cursor-not-allowed items-center justify-between gap-2 rounded-xl bg-blue-mist/40 px-3 py-2.5 text-sm font-semibold text-muted ring-1 ring-dashed ring-line"
        >
          {o.label}
          <span className="text-[10px]">需平台授权</span>
        </span>
      ))}
    </div>
  )
}

const toggleIn = (setter: (fn: (prev: Set<string>) => Set<string>) => void, code: string) =>
  setter((prev) => {
    const next = new Set(prev)
    if (next.has(code)) next.delete(code)
    else next.add(code)
    return next
  })

/** 新增员工抽屉：字段、校验与提交体与旧内嵌表单完全一致，仅承载方式改为右侧抽屉。 */
function OnboardDrawer({ open, busy, onCancel, onSubmit }: {
  open: boolean
  busy: boolean
  onCancel: () => void
  onSubmit: (body: OnboardEmployeeBody) => void
}) {
  return (
    <Drawer
      open={open}
      title="新增员工"
      description="将已有 B 端账号加入本店"
      onClose={onCancel}
      footer={
        <div className="flex items-center gap-2.5">
          <button type="button" onClick={onCancel} className="h-10 rounded-xl bg-white px-5 text-sm font-semibold text-ink ring-1 ring-line transition hover:ring-blue">
            取消
          </button>
          <button
            type="submit"
            form="em-onboard-form"
            disabled={busy}
            className={cx('h-10 rounded-xl px-5 text-sm font-bold text-white transition', NAVY, busy && 'opacity-50')}
          >
            {busy ? '提交中…' : '确认纳编'}
          </button>
        </div>
      }
    >
      <OnboardForm onSubmit={onSubmit} />
    </Drawer>
  )
}

function OnboardForm({ onSubmit }: { onSubmit: (body: OnboardEmployeeBody) => void }) {
  const [phone, setPhone] = useState('')
  const [name, setName] = useState('')
  const [employeeNo, setEmployeeNo] = useState('')
  const [hiredAt, setHiredAt] = useState('')
  const [roles, setRoles] = useState<Set<string>>(new Set())
  const [err, setErr] = useState<string | null>(null)

  const submit = (ev: FormEvent) => {
    ev.preventDefault()
    if (!/^1[3-9]\d{9}$/.test(phone)) return setErr('手机号格式不正确（11 位，1 开头）')
    if (!name.trim()) return setErr('请填写姓名')
    if (roles.size === 0) return setErr('至少授予一个角色——没有角色的人在本店既看不了任务也接不了指派')
    setErr(null)
    onSubmit({ phone, name: name.trim(), employeeNo: employeeNo.trim() || undefined, hiredAt: hiredAt || undefined, roleCodes: [...roles] })
  }

  return (
    <form id="em-onboard-form" onSubmit={submit} className="flex flex-col gap-4">
      <p className="text-[11px] leading-relaxed text-muted">
        手机号需为已注册的 B 端账号（注册要短信验证码，店长不能替对方设密码）；已是别店员工时只加入本店归属，
        <span className="font-semibold text-ink">不改其姓名与工号</span>。
      </p>
      <div className="grid gap-3 sm:grid-cols-2">
        <label className="flex flex-col gap-1 text-xs text-muted">
          手机号 <span className="text-[#E8506B]">*</span>
          <input value={phone} onChange={(e) => setPhone(e.target.value)} inputMode="numeric" placeholder="11 位手机号" aria-label="手机号" className={FIELD} />
        </label>
        <label className="flex flex-col gap-1 text-xs text-muted">
          姓名 <span className="text-[#E8506B]">*</span>
          <input value={name} onChange={(e) => setName(e.target.value)} maxLength={50} placeholder="真实姓名" aria-label="姓名" className={FIELD} />
        </label>
        <label className="flex flex-col gap-1 text-xs text-muted">
          工号（选填）
          <input value={employeeNo} onChange={(e) => setEmployeeNo(e.target.value)} maxLength={32} placeholder="如 E-102" aria-label="工号" className={FIELD} />
        </label>
        <label className="flex flex-col gap-1 text-xs text-muted">
          入职日期（选填，缺省今日）
          <input type="date" value={hiredAt} onChange={(e) => setHiredAt(e.target.value)} aria-label="入职日期" className={FIELD} />
        </label>
      </div>

      <div>
        <p className="text-xs font-semibold text-ink">
          本店角色 <span className="font-normal text-muted">（可多选 = 兼岗）</span>
        </p>
        <div className="mt-2">
          <OptionCards
            options={STORE_ASSIGNABLE_ROLES.map((c) => ({ code: c, label: ROLE_LABEL[c] ?? c }))}
            locked={ADMIN_ONLY_ROLES.map((c) => ({ code: c, label: ROLE_LABEL[c] ?? c }))}
            picked={roles}
            onToggle={(code) => toggleIn(setRoles, code)}
          />
        </div>
      </div>

      {err && <p className="rounded-xl bg-[#FDF0F3] px-3 py-2 text-xs font-semibold text-[#C63B57]">{err}</p>}
    </form>
  )
}

/**
 * 批量排班抽屉（宽版）：三步结构（选人 → 班次 → 重复日期）+ 提交摘要。
 * 候选**独立加载**（`useScheduleCandidates`）：恒拉全店 ALL 名册并按排班资格过滤，
 * 不受员工管理页当前 tab / 关键词 / 可见列表影响（C9 审计缺陷修复）。
 */
function ShiftDrawer({ open, busy, storeId, preset, onCancel, onSubmit }: {
  open: boolean
  busy: boolean
  storeId: string | null
  preset: EmployeeRow | null
  onCancel: () => void
  onSubmit: (body: BatchGenerateBody) => void
}) {
  const cand = useScheduleCandidates(open, storeId)
  // 快照 storeId 必须与当前门店一致才可用——切店瞬间的旧店就绪快照一律视为未就绪，杜绝沿用另一门店候选
  const ready = cand.phase === 'ready' && cand.storeId === storeId
  const eligible = ready && cand.phase === 'ready'
    ? cand.rows.filter((r) => isScheduleEligible(r, storeId))
    : []
  // 候选未就绪（加载中 / 失败 / 切店失配）时禁止提交，避免提交尚未加载或不完整的候选
  const submitDisabled = busy || !ready
  return (
    <Drawer
      open={open}
      title={preset ? `安排排班 · ${preset.name}` : '批量排班'}
      description="为选中员工生成班次；排完班的人当天才会出现在 C 端可约候选里"
      width="sm:max-w-[660px]"
      onClose={onCancel}
      footer={
        <div className="flex items-center gap-2.5">
          <button type="button" onClick={onCancel} className="h-10 rounded-xl bg-white px-5 text-sm font-semibold text-ink ring-1 ring-line transition hover:ring-blue">
            取消
          </button>
          <button
            type="submit"
            form="em-shift-form"
            disabled={submitDisabled}
            className={cx('h-10 rounded-xl px-5 text-sm font-bold text-white transition', NAVY, submitDisabled && 'opacity-50')}
          >
            {busy ? '提交中…' : '生成班次'}
          </button>
        </div>
      }
    >
      <ShiftForm
        cand={cand}
        eligible={eligible}
        preset={preset}
        onRetry={() => void loadCandidates(storeId)}
        onSubmit={onSubmit}
      />
    </Drawer>
  )
}

/**
 * 排班表单（ADR-034）。从行内「安排排班」进来时预置该员工，从顶部「批量排班」进来时自己勾人。
 *
 * <p>诚实性两条：撞 uk_emp_date 的人日后端不会改动（回执 skipped 里点名所属门店），
 * 所以文案是"未改动"而不是"失败"；一日一班是**全平台**口径，同一个人被两店排到同一天是常态而非异常。
 */
function ShiftForm({ cand, eligible, preset, onRetry, onSubmit }: {
  cand: CandidatesState
  eligible: EmployeeRow[]
  preset: EmployeeRow | null
  onRetry: () => void
  onSubmit: (body: BatchGenerateBody) => void
}) {
  const [picked, setPicked] = useState<Set<string>>(new Set(preset ? [preset.employeeId] : []))
  const [startDate, setStartDate] = useState(localDate(1))
  const [endDate, setEndDate] = useState(localDate(1))
  const [startTime, setStartTime] = useState('09:00')
  const [endTime, setEndTime] = useState('18:00')
  const [weekdays, setWeekdays] = useState<Set<number>>(new Set([1, 2, 3, 4, 5]))
  const [err, setErr] = useState<string | null>(null)

  const toggleEmp = (id: string) => setPicked((prev) => {
    const next = new Set(prev)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    return next
  })
  const toggleDay = (w: number) => setWeekdays((prev) => {
    const next = new Set(prev)
    if (next.has(w)) next.delete(w)
    else next.add(w)
    return next
  })

  const submit = (ev: FormEvent) => {
    ev.preventDefault()
    if (picked.size === 0) return setErr('至少选择一名员工')
    if (!startDate || !endDate) return setErr('请填写开始日与结束日')
    if (endDate < startDate) return setErr('结束日不得早于开始日')
    if (startDate < localDate(0)) return setErr('不能排今天之前的班次（历史班没有任何消费方）')
    const span = Math.round((Date.parse(`${endDate}T00:00:00`) - Date.parse(`${startDate}T00:00:00`)) / 86400000) + 1
    if (span > SCHEDULE_MAX_SPAN_DAYS) return setErr(`排班区间最长 ${SCHEDULE_MAX_SPAN_DAYS} 天`)
    if (endTime <= startTime) return setErr('下班时间必须晚于上班时间')
    setErr(null)
    onSubmit({
      employeeIds: [...picked],
      startDate,
      endDate,
      startTime,
      endTime,
      // 全选或全不选 = 不筛（省略参数），否则后端会按勾选的日子排
      weekdays: weekdays.size >= 7 || weekdays.size === 0 ? undefined : [...weekdays].sort((a, b) => a - b),
    })
  }

  /** 摘要用的星期文案：全不勾/全勾=每天；恰工作日=周一至周五；其余逐个列出。 */
  const weekdayText = (() => {
    const arr = [...weekdays].sort((a, b) => a - b)
    if (arr.length === 0 || arr.length === 7) return '每天'
    if (arr.join(',') === '1,2,3,4,5') return '周一至周五'
    return arr.map((w) => `周${WEEKDAY_LABEL[w]}`).join('、')
  })()

  return (
    <form id="em-shift-form" onSubmit={submit} className="flex flex-col gap-5">
      {/* ① 选择员工 */}
      <section>
        <StepTitle n={1} text="选择员工" extra={<span className={cx('ml-auto text-xs font-semibold', picked.size ? 'text-blue' : 'text-muted')}>已选择 {picked.size} 人</span>} />
        <div className="mt-2 max-h-[220px] overflow-y-auto rounded-xl ring-1 ring-line">
          {cand.phase === 'idle' || cand.phase === 'loading' ? (
            <p className="px-3 py-6 text-center text-xs text-muted">
              <Spinner /> 加载可排班员工…
            </p>
          ) : cand.phase === 'error' ? (
            <div className="flex flex-col items-center gap-2 px-3 py-6">
              <p className="text-center text-xs text-[#E8506B]">候选加载失败：{cand.message}</p>
              <button type="button" onClick={onRetry} className="h-8 rounded-lg bg-white px-4 text-xs font-semibold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue">
                重试
              </button>
            </div>
          ) : eligible.length === 0 ? (
            <p className="px-3 py-6 text-center text-xs leading-relaxed text-muted">
              本店当前没有可排班的在职员工
              <span className="mt-1 block text-[11px] text-muted/80">离职 / 停用 / 请假中 / 已移出本店者不可排班</span>
            </p>
          ) : (
            <div className="divide-y divide-line">
              {eligible.map((c) => {
                const on = picked.has(c.employeeId)
                const duty = c.todayShift?.status
                return (
                  <label key={c.employeeId} className={cx('flex cursor-pointer items-center gap-3 px-3 py-2.5 transition', on ? 'bg-blue-soft/70' : 'hover:bg-blue-mist/50')}>
                    <span className={cx('grid h-[18px] w-[18px] shrink-0 place-items-center rounded-full ring-1 transition', on ? 'bg-blue ring-blue' : 'bg-white ring-line')}>
                      <span className={cx('h-2 w-2 rounded-full bg-white transition', !on && 'opacity-0')} />
                    </span>
                    <input type="checkbox" className="sr-only" checked={on} onChange={() => toggleEmp(c.employeeId)} />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-bold text-ink">{c.name}</span>
                      <span className="block truncate text-[11px] text-muted">
                        {roleLabelsOf(c.roleCodes)} · {duty === 'WORKING' ? '在班' : duty === 'LEAVE' ? '请假' : '未排班'}
                      </span>
                    </span>
                  </label>
                )
              })}
            </div>
          )}
        </div>
      </section>

      {/* ② 设置班次 */}
      <section>
        <StepTitle n={2} text="设置班次" />
        <div className="mt-2 grid gap-3 sm:grid-cols-4">
          <label className="flex flex-col gap-1 text-xs text-muted">
            开始日期
            <input type="date" value={startDate} min={localDate(0)} onChange={(e) => setStartDate(e.target.value)} aria-label="开始日" className={FIELD} />
          </label>
          <label className="flex flex-col gap-1 text-xs text-muted">
            结束日期
            <input type="date" value={endDate} min={startDate} onChange={(e) => setEndDate(e.target.value)} aria-label="结束日" className={FIELD} />
          </label>
          <label className="flex flex-col gap-1 text-xs text-muted">
            上班时间
            <input type="time" value={startTime} onChange={(e) => setStartTime(e.target.value)} aria-label="上班时间" className={FIELD} />
          </label>
          <label className="flex flex-col gap-1 text-xs text-muted">
            下班时间
            <input type="time" value={endTime} onChange={(e) => setEndTime(e.target.value)} aria-label="下班时间" className={FIELD} />
          </label>
        </div>
      </section>

      {/* ③ 重复日期 */}
      <section>
        <StepTitle n={3} text="重复日期" extra={<span className="ml-auto text-[11px] text-muted">全不勾或全勾 = 区间内每天</span>} />
        <div className="mt-2 flex flex-wrap gap-2">
          {[1, 2, 3, 4, 5, 6, 7].map((w) => {
            const on = weekdays.has(w)
            return (
              <button
                key={w}
                type="button"
                aria-pressed={on}
                onClick={() => toggleDay(w)}
                className={cx(
                  'h-9 min-w-[54px] rounded-lg text-sm font-semibold ring-1 transition',
                  on ? 'bg-blue text-white ring-blue' : 'bg-white text-ink ring-line hover:ring-blue/50',
                )}
              >
                周{WEEKDAY_LABEL[w]}
              </button>
            )
          })}
        </div>
      </section>

      {/* 提交摘要（轻量，不做大卡片） */}
      <div className="rounded-xl bg-blue-mist/60 px-4 py-3 ring-1 ring-line">
        <p className="text-[13px] font-bold text-ink">将为 {picked.size} 名员工生成排班</p>
        <p className="mt-1 font-mono text-[11px] leading-relaxed text-muted">
          {startDate} ~ {endDate} · {startTime} - {endTime} · {weekdayText}
        </p>
        <p className="mt-1 text-[10px] leading-relaxed text-muted/80">一人一天只能有一个班；已有班的日期后端不改动，仅在回执里点名所属门店。</p>
      </div>

      {err && <p className="rounded-xl bg-[#FDF0F3] px-3 py-2 text-xs font-semibold text-[#C63B57]">{err}</p>}
    </form>
  )
}

function StepTitle({ n, text, extra }: { n: number; text: string; extra?: ReactNode }) {
  return (
    <p className="flex items-center gap-2 text-[13px] font-bold text-ink">
      <span className="grid h-5 w-5 shrink-0 place-items-center rounded-full bg-blue/10 text-[11px] font-black text-blue">{n}</span>
      {text}
      {extra}
    </p>
  )
}

function Spinner() {
  return <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-blue border-t-transparent align-[-2px]" aria-hidden="true" />
}

function ListLoading() {
  return (
    <div className="flex flex-col gap-3" role="status" aria-label="员工列表加载中">
      {[0, 1, 2].map((i) => (
        <div key={i} className="flex h-[92px] items-center justify-center rounded-2xl bg-blue-mist/60 ring-1 ring-line">
          {i === 0 && (
            <span className="text-xs text-muted">
              <Spinner /> 加载员工列表…
            </span>
          )}
        </div>
      ))}
    </div>
  )
}

function ListError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-2xl bg-blue-mist/60 py-12 ring-1 ring-line">
      <p className="text-sm text-[#E8506B]">员工列表加载失败：{message}</p>
      <button type="button" onClick={onRetry} className="h-9 rounded-lg border border-line bg-white px-5 text-xs font-semibold text-ink transition hover:border-blue hover:text-blue">
        重试
      </button>
      <p className="max-w-[420px] text-center text-[11px] leading-relaxed text-muted">
        若提示无权限，请联系平台管理员开通员工查看权限。
      </p>
    </div>
  )
}
