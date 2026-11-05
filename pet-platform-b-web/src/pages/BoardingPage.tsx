import { useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import Layout from '../components/layout/Layout'
import { AREA, CareForm, FormShell, INPUT } from '../components/care/CareForm'
import { useToast } from '../components/ui/Toast'
import { storeContext } from '../auth/storeContext'
import { BizError } from '../api/types'
import type { AppointmentItem, PageData } from '../api/dashboard'
import { fenToYuanText } from '../api/dashboard'
import { SPECIES_LABEL, type PetSpecies } from '../api/pets'
import {
  boardingApi,
  careSummary,
  HISTORY_PAGE_SIZE,
  isInStore,
  KENNEL_TYPE_LABEL,
  shortDateTime,
  STAY_STATUS_LABEL,
  stayNightsText,
  type CareRecord,
  type CareRecordBody,
  type CheckinBody,
  type HistoryStayStatus,
  type KennelCapacity,
  type KennelOption,
  type KennelType,
  type PendingBoardingAppointment,
  type StayDetail,
  type StayItem,
  type StayStatus,
  type StoreStaff,
} from '../api/boarding'
import {
  IconBulb,
  IconCheckCircle,
  IconChevronDown,
  IconChevronRight,
  IconClock,
  IconHeartPulse,
  IconHouse,
  IconPaw,
  IconPlus,
  IconSearch,
  IconXCircle,
} from '../components/ui/icons'
import { cx } from '../lib/cx'
import { TONE } from '../lib/tone'
import { mediaSrc } from '../lib/mediaSrc'
import type { Tone } from '../mock/dashboard'
import { TIPS } from '../mock/boardings'
import {
  boardingCapabilities,
  canCareForStay,
  type BoardingCapabilities,
} from '../lib/boardingPermission'
import {
  ACTIVITY_PREVIEW_LIMIT,
  BOARDING_PAGE_SIZE,
  BOARDING_TAB_STATUSES,
  CARE_ACTIVITY_LIMIT,
  CARE_RECORD_LIMIT,
  KEYWORD_DEBOUNCE_MS,
  PENDING_BOARDING_LIMIT,
  boundedCountText,
  clampPageAfterLoad,
  pageCount,
  stayPageQueryKey,
  statusCountsKey,
  type BoardingTab,
} from '../lib/boardingView'
import type { StayStatusCounts } from '../api/boarding'

const NAVY = 'bg-[#1E3A5F] text-white hover:bg-[#16304F]'
/** 项目主色按钮规范（与宠物管理页「添加宠物」同款） */
const PRIMARY_BTN = 'bg-blue text-white shadow-soft hover:bg-blue-deep'
/**
 * 三区卡片骨架，照搬宠物管理页 PetRow 的做法：**头像列与操作列给确定宽度**，
 * 中间信息区用 minmax(0,1fr) 吸收剩余。
 * 上一版是「每行一个 grid + 多列 auto」，auto 按该行自身内容算宽 → 各行列边界互不相同，
 * 客户/日期/状态/护理员自然对不齐；本轮改为固定列 + 卡内 2×2 信息网格，对齐由列模板保证。
 */
const CARD_GRID = 'grid grid-cols-1 gap-x-5 gap-y-3 p-4 xl:grid-cols-[230px_minmax(0,1fr)_244px] xl:items-center'
/** 托管信息区：四格固定顺序（客户 / 计划日期 / 状态 / 主责护理员），窄屏退化为单列 */
const INFO_GRID = 'grid grid-cols-1 gap-x-4 gap-y-2 sm:grid-cols-2'

const STATUS_LABEL: Record<StayStatus, string> = STAY_STATUS_LABEL
/** 关联预约状态文案（仅离店一致性提示用，非住单状态机） */
const APT_STATUS_LABEL: Record<string, string> = {
  PENDING_PAYMENT: '待支付',
  CONFIRMED: '已确认（未入住）',
  WAIT_SERVICE: '待服务',
  SERVING: '服务中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  CANCEL_REQUESTED: '取消审核中',
}
const STATUS_TONE: Record<StayStatus, Tone> = {
  EXPECTED: 'blue',
  CHECKED_IN: 'green',
  DAILY_CARE: 'purple',
  CHECKED_OUT: 'gray',
  ABNORMAL: 'pink',
}

/**
 * 页签定义：状态集合与计数取值都在这一处，避免"页签显示 5 条、列表请求另一种状态"两张皮。
 * pick 读的是服务端 status-counts 的字段（long 被字符串化 → Number()）。
 */
const STATUS_TABS: { key: BoardingTab; label: string; pick: (c: StayStatusCounts) => string }[] = [
  { key: 'ALL', label: '全部', pick: (c) => c.all },
  { key: 'IN_STORE', label: '在店中', pick: (c) => c.inStore },
  { key: 'EXPECTED', label: '待入住', pick: (c) => c.expected },
  { key: 'CHECKED_OUT', label: '已离店', pick: (c) => c.checkedOut },
  { key: 'ABNORMAL', label: '异常', pick: (c) => c.abnormal },
]

const PAGER_BTN = 'h-7 rounded-full px-2.5 font-semibold text-ink ring-1 ring-line transition hover:text-blue disabled:opacity-40 disabled:hover:text-ink'

type FormKind = 'checkin' | 'care' | 'checkout' | 'caretaker'

/** 写侧动作装配（入住/离店/护理记录/改派护理员全走真实端点；一次只开一个表单） */
interface RowActions {
  myEmployeeId: string | null
  caps: BoardingCapabilities
  busy: boolean
  form: { kind: FormKind; stayId: string } | null
  onOpen: (kind: FormKind, stayId: string) => void
  onClose: () => void
  onCheckin: (stayId: string, body: CheckinBody) => void
  onCheckout: (stayId: string) => void
  onCare: (stayId: string, body: CareRecordBody) => void
  onAssignCaretaker: (stayId: string, caretakerId: string) => void
}

/**
 * 托管服务页（紧凑工作台）。列表/详情/护理记录/笼位容量为真实只读接口，
 * 入住/离店/新增护理记录为真实写接口（ADR-015 状态机），服务端权威校验、前端只做防呆与二次确认。
 *
 * <p>V3 起主列表走 `GET /store/boarding-stays/page`：分页与关键词都在**服务端**完成，
 * 页签角标走 `status-counts`（一次 GROUP BY，与列表同一份过滤条件），
 * 因此搜索覆盖本店全部符合条件的住单、计数是准确值——不再有"只在前 50 条里找"的盲区。
 * 历史页签（已离店/异常）仍走 ADR-032 的历史分页端点（独立状态范围与排序，不与主列表混算）。
 */
export default function BoardingPage() {
  const { push } = useToast()
  const ctx = useSyncExternalStore(storeContext.subscribe, storeContext.get)
  const storeId = ctx.storeId
  const myEmployeeId = ctx.identity?.employeeId ?? null
  const currentStoreRoles = ctx.candidates.find((s) => s.storeId === storeId)?.roles ?? []
  const caps = boardingCapabilities(ctx.platformAdmin === true, currentStoreRoles)
  const [searchParams] = useSearchParams()

  const [tab, setTab] = useState<BoardingTab>('ALL')
  /** 输入框里的原值与"真正发请求的关键词"分开：逐字符打服务端会把一次输入变成十次查询 */
  const [keywordInput, setKeywordInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [pageNum, setPageNum] = useState(1)
  /** 写成功后推进列表+角标一起重取（同一次操作只各打一次） */
  const [writeTick, setWriteTick] = useState(0)

  useEffect(() => {
    const t = setTimeout(() => setKeyword(keywordInput.trim()), KEYWORD_DEBOUNCE_MS)
    return () => clearTimeout(t)
  }, [keywordInput])
  // 关键词与页签都会改变结果集，停在第 3 页再换条件等于看一个不存在的页
  useEffect(() => { setPageNum(1) }, [keyword, tab, storeId])

  const list = useAsync(
    () => boardingApi.stayPage({
      statuses: BOARDING_TAB_STATUSES[tab],
      keyword: keyword || undefined,
      pageNum,
      pageSize: BOARDING_PAGE_SIZE,
    }),
    stayPageQueryKey(storeId, tab, keyword, pageNum),
  )
  const counts = useAsync(
    () => boardingApi.statusCounts(keyword || undefined),
    statusCountsKey(storeId, keyword, writeTick),
  )
  /** 笼位容量是独立权限码（boarding:capacity:view），无权限时不发这一枪 */
  const capacity = useAsync<KennelCapacity[]>(
    () => boardingApi.capacity(),
    caps.capacity && storeId ? storeId : null,
  )
  /** 待支付托管预约（ADR-030）：独立成块降级，拉不到只影响这一张卡，不拖累主列表 */
  const pending = useAsync<PendingBoardingAppointment[]>(() => boardingApi.pendingAppointments(), storeId)

  /**
   * 历史页签（已离店/异常）走分页端点（ADR-032 片2）。页码/日期任一变化都要重新请求，
   * 故 reloadKey 拼成一条串；非历史页签传 null → useAsync 不发请求。
   */
  const [histPage, setHistPage] = useState(1)
  const [histFrom, setHistFrom] = useState('')
  const [histTo, setHistTo] = useState('')
  const isHistTab = tab === 'CHECKED_OUT' || tab === 'ABNORMAL'
  const history = useAsync(
    () => boardingApi.history({
      status: tab as HistoryStayStatus,
      pageNum: histPage,
      dateFrom: histFrom || undefined,
      dateTo: histTo || undefined,
    }),
    isHistTab && storeId ? `${storeId}|${tab}|${histPage}|${histFrom}|${histTo}` : null,
  )
  // 换页签/换店必须把页码打回 1：停在历史第 3 页再切页签会落到空页
  useEffect(() => { setHistPage(1) }, [tab, storeId])

  const [openId, setOpenId] = useState<string | null>(
    // 消息中心深链（ADR-047）：照护异常那条消息的 bizId 就是住单 id。定位尽力而为：
    // 该住单不在当前页签/当前页（例如已离店）时就是不高亮，不为此偷偷改页签。
    searchParams.get('openId') || null,
  )
  const [provisionOpen, setProvisionOpen] = useState(false)
  const [provisioningId, setProvisioningId] = useState<string | null>(null)
  /** 一次只开一个写操作表单（入住/护理/离店确认），避免多行状态散落 */
  const [form, setForm] = useState<{ kind: FormKind; stayId: string } | null>(null)
  const [busy, setBusy] = useState(false)
  // 面板未打开/无补建权限时 reloadKey 传 null → 不发候选请求
  const candidates = useAsync(
    () => boardingApi.provisionCandidates(),
    provisionOpen && caps.provision ? storeId : null,
  )

  /**
   * 切店收口：清搜索（输入框与生效关键词都要清）/页码/历史日期/展开项/表单/补建面板与临时状态，
   * 页签回到「全部」；同时推进写纪元，旧门店在途写结果不得再改新门店 UI。
   * 列表与角标的 reloadKey 含门店，换店即自动重取；旧门店的迟到响应由 useAsync 的 seq 丢弃。
   * 首次挂载不执行（保留 URL openId 深链）。
   */
  const prevStoreRef = useRef(storeId)
  const writeEpochRef = useRef(0)
  useEffect(() => {
    const prev = prevStoreRef.current
    if (prev === storeId) return
    prevStoreRef.current = storeId
    // 上下文从「未就绪」建立到首家门店：没有旧门店 UI/写请求可清，保留 URL openId 深链
    if (prev === null) return
    writeEpochRef.current += 1
    setTab('ALL')
    setKeywordInput('')
    setKeyword('')
    setPageNum(1)
    setHistPage(1)
    setHistFrom('')
    setHistTo('')
    setOpenId(null)
    setForm(null)
    setProvisionOpen(false)
    setProvisioningId(null)
    setBusy(false)
  }, [storeId])

  const errMsg = (err: unknown, fallback: string) => (err instanceof BizError ? err.message : fallback)
  /** 写结果只允许落在发起写的那家店；切店后旧响应连 toast/关闭面板都不做 */
  const isWriteCurrent = (epoch: number, writeStore: string | null) =>
    epoch === writeEpochRef.current && storeContext.get().storeId === writeStore

  const refreshAfterWrite = () => {
    list.reload()
    capacity.reload()
    setWriteTick((t) => t + 1)
  }

  const onProvision = async (appointmentId: string) => {
    const epoch = writeEpochRef.current
    const writeStore = storeId
    setProvisioningId(appointmentId)
    try {
      const stay = await boardingApi.provision(appointmentId)
      if (!isWriteCurrent(epoch, writeStore)) return
      push(`已生成待入住单 #${stay.id}`, 'success')
      setProvisionOpen(false)
      list.reload()
      setWriteTick((t) => t + 1)
    } catch (err) {
      if (!isWriteCurrent(epoch, writeStore)) return
      push(errMsg(err, '生成失败，请重试'), 'error')
    } finally {
      if (epoch === writeEpochRef.current) setProvisioningId(null)
    }
  }

  /** 写操作统一收口：在途禁按钮（无 requestId 幂等端点，防连点靠服务端条件更新 + 前端串行） */
  const runWrite = async (
    label: 'checkin' | 'checkout' | 'care' | 'caretaker',
    call: () => Promise<StayDetail | CareRecord>,
  ) => {
    const epoch = writeEpochRef.current
    const writeStore = storeId
    setBusy(true)
    try {
      const out = await call()
      if (!isWriteCurrent(epoch, writeStore)) return
      if (label === 'checkin' || label === 'checkout') {
        const v = out as StayDetail
        push(`${label === 'checkin' ? '已办理入住' : '已办理离店'}：状态 ${STAY_STATUS_LABEL[v.status] ?? v.status}`, 'success')
      } else if (label === 'caretaker') {
        push('主责护理员已更新，本格展示与护理记录署名归属随之切换', 'success')
      } else {
        push('护理记录已提交', 'success')
      }
      setForm(null)
      refreshAfterWrite()
    } catch (err) {
      if (!isWriteCurrent(epoch, writeStore)) return
      push(errMsg(err, '操作失败，请重试'), 'error')
      list.reload() // 状态可能已被他人推进（条件更新 0 行），拉一次真相
    } finally {
      if (epoch === writeEpochRef.current) setBusy(false)
    }
  }

  const actions: RowActions = {
    myEmployeeId,
    caps,
    busy,
    form,
    onOpen: (kind, stayId) => setForm((v) => (v && v.stayId === stayId && v.kind === kind ? null : { kind, stayId })),
    onClose: () => setForm(null),
    onCheckin: (stayId, body) => void runWrite('checkin', () => boardingApi.checkin(stayId, body)),
    onCheckout: (stayId) => void runWrite('checkout', () => boardingApi.checkout(stayId)),
    onCare: (stayId, body) => void runWrite('care', () => boardingApi.addCareRecord(stayId, body)),
    onAssignCaretaker: (stayId, caretakerId) => void runWrite('caretaker', () => boardingApi.assignCaretaker(stayId, caretakerId)),
  }

  const rows: StayItem[] = list.block.state !== 'ready' ? [] : list.block.data.list
  /** total 是后端 long → Jackson 字符串化；它是**当前筛选条件下的准确匹配总数**，不是本页条数 */
  const total = list.block.state === 'ready' ? Number(list.block.data.total) : 0
  const pages = pageCount(total, BOARDING_PAGE_SIZE)
  // 别人先办完/删掉会让总页数变小，停在原页码就是"看着像没数据"的空页 → 回到最后一个有效页重取
  useEffect(() => {
    if (list.block.state !== 'ready') return
    const next = clampPageAfterLoad(pageNum, total, BOARDING_PAGE_SIZE)
    if (next !== pageNum) setPageNum(next)
  }, [list.block.state, total, pageNum])
  // 内联判窄（先判 state 再取 data），不靠布尔变量传递窄化结果，也不用 as 强转
  const histRows: StayItem[] = !isHistTab || history.block.state !== 'ready' ? [] : history.block.data.list
  const histTotal = !isHistTab || history.block.state !== 'ready' ? 0 : Number(history.block.data.total)
  const histPages = Math.max(1, Math.ceil(histTotal / HISTORY_PAGE_SIZE))
  const keywordActive = keyword.length > 0

  return (
    <Layout active="boarding">
      <div className="grid gap-4 p-4 sm:p-6 2xl:grid-cols-[minmax(0,1fr)_300px]">
        <div className="flex min-w-0 flex-col gap-4">
          <BoardingHeader
            onCreate={() => setProvisionOpen((v) => !v)}
            createOpen={provisionOpen}
            disabled={!caps.provision}
          />

          {provisionOpen && caps.provision && (
            <ProvisionPanel
              block={candidates.block}
              existingAppointmentIds={new Set(rows.map((s) => s.appointmentId))}
              busyId={provisioningId}
              onProvision={(id) => void onProvision(id)}
              onRetry={candidates.reload}
              onClose={() => setProvisionOpen(false)}
            />
          )}

          <PendingBoardingPanel
            block={pending.block}
            onRetry={pending.reload}
            customerVisible={caps.customer}
          />

          <section className="rounded-2xl bg-white p-3.5 shadow-soft ring-1 ring-line">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex flex-wrap items-center gap-1.5" role="tablist" aria-label="托管状态筛选">
                {STATUS_TABS.map(({ key, label, pick }) => (
                  <button
                    key={key}
                    type="button"
                    role="tab"
                    aria-selected={tab === key}
                    onClick={() => setTab(key)}
                    className={cx(
                      'inline-flex h-8 items-center gap-1.5 rounded-lg px-3 text-xs font-semibold transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue/40',
                      tab === key
                        ? 'bg-blue text-white shadow-soft'
                        : 'bg-white text-muted ring-1 ring-line hover:text-blue',
                    )}
                  >
                    {label}
                    <span
                      className={cx(
                        'rounded px-1 text-[11px] font-bold leading-4',
                        tab === key ? 'bg-white/20 text-white' : 'bg-blue-mist text-muted',
                      )}
                    >
                      {counts.block.state === 'ready' ? Number(pick(counts.block.data)) : '…'}
                    </span>
                  </button>
                ))}
                {counts.block.state === 'error' && (
                  <button
                    type="button"
                    onClick={counts.reload}
                    className="text-[11px] text-muted underline-offset-2 transition hover:text-blue hover:underline"
                  >
                    角标计数不可用，点此重试
                  </button>
                )}
              </div>

              {isHistTab ? (
                <div className="flex flex-wrap items-center gap-2">
                  <label className="flex items-center gap-1.5 text-[11px] text-muted">
                    离店日起
                    <input type="date" value={histFrom} max={histTo || undefined} onChange={(e) => setHistFrom(e.target.value)} className="h-8 rounded-lg bg-white px-2 text-xs text-ink outline-none ring-1 ring-line focus:ring-blue" />
                  </label>
                  <label className="flex items-center gap-1.5 text-[11px] text-muted">
                    至
                    <input type="date" value={histTo} min={histFrom || undefined} max={localToday()} onChange={(e) => setHistTo(e.target.value)} className="h-8 rounded-lg bg-white px-2 text-xs text-ink outline-none ring-1 ring-line focus:ring-blue" />
                  </label>
                  {(histFrom || histTo) && (
                    <button type="button" onClick={() => { setHistFrom(''); setHistTo('') }} className="h-8 rounded-lg px-2.5 text-[11px] font-semibold text-muted ring-1 ring-line transition hover:text-blue">
                      清除
                    </button>
                  )}
                </div>
              ) : (
                <label className="relative w-full sm:w-80">
                  <IconSearch className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
                  <input
                    value={keywordInput}
                    onChange={(e) => setKeywordInput(e.target.value)}
                    placeholder="搜索本店全部住单：宠物名/客户昵称/手机号/住单号/预约号"
                    className="h-8 w-full rounded-lg bg-white pl-9 pr-3 text-[13px] text-ink outline-none ring-1 ring-line transition placeholder:text-muted focus:ring-blue"
                  />
                </label>
              )}
            </div>
            {isHistTab ? (
              <p className="mt-2 text-[11px] leading-relaxed text-muted">
                历史按离店日期翻页，不支持关键词搜索（ADR-032 §3），日期区间打在离店日上；
                上方角标是「关键词 + 本店全部历史住单」的准确条数，不受日期窗口影响。
              </p>
            ) : (
              <p className="mt-2 text-[11px] leading-relaxed text-muted">
                搜索由服务端执行，覆盖本店当前页签**全部**符合条件的住单（宠物名 / 客户昵称 / 手机号 / 住单号 / 预约号）；
                角标与「共 N 条」为服务端准确计数。
              </p>
            )}
          </section>

          <div className="flex min-w-0 flex-col gap-3">
            {isHistTab && history.block.state === 'loading' && <BlockLoading text="正在加载历史住单…" />}
            {isHistTab && history.block.state === 'error' && <BlockError message={history.block.message} onRetry={history.reload} />}
            {isHistTab && history.block.state === 'ready' && histRows.length === 0 && (
              <p className="rounded-xl bg-white py-10 text-center text-sm text-muted ring-1 ring-line">
                {histTotal === 0 ? '该状态下暂无历史住单' : '本页没有记录，试试上一页'}
              </p>
            )}
            {isHistTab && history.block.state === 'ready' &&
              histRows.map((s) => (
                <StayRow
                  key={s.id}
                  stay={s}
                  open={openId === s.id}
                  onToggle={() => setOpenId((v) => (v === s.id ? null : s.id))}
                  refreshKey={writeTick}
                  actions={actions}
                />
              ))}
            {isHistTab && history.block.state === 'ready' && histTotal > 0 && (
              <div className="flex items-center justify-center gap-3 text-[11px] text-muted">
                <button type="button" disabled={histPage <= 1} onClick={() => setHistPage((p) => Math.max(1, p - 1))} className={PAGER_BTN}>上一页</button>
                <span>第 {history.block.data.pageNum} / {histPages} 页 · 共 {histTotal} 条</span>
                <button type="button" disabled={histPage >= histPages} onClick={() => setHistPage((p) => p + 1)} className={PAGER_BTN}>下一页</button>
              </div>
            )}

            {!isHistTab && list.block.state === 'loading' && <BlockLoading text="正在加载托管住单…" />}
            {!isHistTab && list.block.state === 'error' && <BlockError message={list.block.message} onRetry={list.reload} />}
            {!isHistTab && list.block.state === 'ready' && rows.length === 0 && (
              <p className="rounded-xl bg-white py-10 text-center text-sm text-muted ring-1 ring-line">
                {total === 0
                  ? keywordActive
                    ? `没有匹配「${keyword}」的托管住单（已搜索本店该页签下的全部住单）`
                    : '当前门店该页签下暂无托管住单'
                  : '本页没有记录，试试上一页'}
              </p>
            )}
            {!isHistTab && list.block.state === 'ready' &&
              rows.map((s) => (
                <StayRow
                  key={s.id}
                  stay={s}
                  open={openId === s.id}
                  onToggle={() => setOpenId((v) => (v === s.id ? null : s.id))}
                  refreshKey={writeTick}
                  actions={actions}
                />
              ))}
            {!isHistTab && list.block.state === 'ready' && total > 0 && (
              <div className="flex flex-wrap items-center justify-center gap-3 text-[11px] text-muted">
                <button type="button" disabled={pageNum <= 1} onClick={() => setPageNum((p) => Math.max(1, p - 1))} className={PAGER_BTN}>上一页</button>
                <span>
                  第 {list.block.data.pageNum} / {pages} 页 · 共 {total} 条
                  {keywordActive ? '（服务端关键词匹配总数）' : `（每页 ${BOARDING_PAGE_SIZE} 条）`}
                </span>
                <button type="button" disabled={pageNum >= pages} onClick={() => setPageNum((p) => p + 1)} className={PAGER_BTN}>下一页</button>
              </div>
            )}
          </div>
        </div>

        <aside className="grid min-w-0 grid-cols-1 items-start gap-4 sm:grid-cols-2 2xl:grid-cols-1">
          <StatsCard block={counts.block} onRetry={counts.reload} keywordActive={keywordActive} />
          <CapacityCard block={capacity.block} onRetry={capacity.reload} enabled={caps.capacity} />
          <TodayActivity storeId={storeId} refreshKey={writeTick} />
          <TipsCard />
        </aside>
      </div>
    </Layout>
  )
}

/* ---- 区块数据（判别联合 + seq 丢弃陈旧响应；三页重复待抽公共 hook） ---- */

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

function localToday(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** 后端 message 即用户可读原因（含 10003/10004 等），降级提示直接用它，不自行编文案 */
function bizMsg(err: unknown): string {
  return err instanceof BizError ? err.message : '加载失败'
}

/* ---- 通用三态 ---- */

function BlockLoading({ text }: { text: string }) {
  return (
    <div className="flex items-center justify-center gap-2 rounded-xl bg-white py-8 text-sm text-muted shadow-soft ring-1 ring-line">
      <i className="h-4 w-4 animate-spin rounded-full border-2 border-line border-t-blue" /> {text}
    </div>
  )
}

function BlockError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="rounded-xl bg-white p-5 text-center shadow-soft ring-1 ring-line">
      <p className="text-sm text-[#E8506B]">{message}</p>
      <button type="button" onClick={onRetry} className="mt-2 h-8 rounded-lg bg-white px-3 text-xs font-bold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue">
        重试
      </button>
    </div>
  )
}

/* ---- 顶部标题区（与宠物管理页同款：标题 + 说明 + 右侧主操作） ---- */

function BoardingHeader({
  onCreate,
  createOpen,
  disabled,
}: {
  onCreate: () => void
  createOpen: boolean
  disabled: boolean
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0">
        <h1 className="text-2xl font-black tracking-tight text-ink">托管服务</h1>
        <p className="mt-1 text-[13px] text-muted">
          在店照护、待入住与历史住单统一视图；护理记录由主责护理员或持护理权限的员工填写（ADR-015 §5）。
        </p>
        <p className="mt-0.5 text-[13px] text-muted">
          「新增托管」不是新建预约：仅从本店今日已确认预约补建待入住单，不创建预约、不修改宠物与日期。
        </p>
      </div>
      <button
        type="button"
        onClick={onCreate}
        disabled={disabled}
        aria-expanded={createOpen}
        title={disabled ? '当前账号无补建权限（需 boarding:checkin 与 appointment:view:store）' : undefined}
        className="flex h-9 shrink-0 items-center gap-1.5 rounded-lg bg-blue px-4 text-[13px] font-bold text-white shadow-soft transition hover:bg-blue-deep focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue/40 disabled:cursor-not-allowed disabled:opacity-50"
      >
        {createOpen ? <IconChevronDown className="h-4 w-4 rotate-180" /> : <IconPlus className="h-4 w-4" />}
        {createOpen ? '收起补建入口' : '新增托管（补建）'}
      </button>
    </div>
  )
}

/* ---- 待支付托管预约（ADR-030：这类预约不生成住单，本卡是它在 B 端唯一的可见出口）---- */

function PendingBoardingPanel({
  block,
  onRetry,
  customerVisible,
}: {
  block: Block<PendingBoardingAppointment[]>
  onRetry: () => void
  customerVisible: boolean
}) {
  const rows = block.state === 'ready' ? block.data : []
  const atLimit = block.state === 'ready' && rows.length >= PENDING_BOARDING_LIMIT
  const badge = block.state === 'ready' ? boundedCountText(rows.length, atLimit) : '…'
  return (
    <section className="rounded-2xl bg-white p-4 shadow-soft ring-1 ring-line">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
        <span
          className="inline-block rounded-full px-2 py-0.5 text-[11px] font-bold"
          style={{ background: TONE.orange.bg, color: TONE.orange.fg }}
        >
          待支付 {badge}
        </span>
        <h2 className="text-sm font-bold text-ink">已下单未付款的托管预约</h2>
        <span className="text-[11px] text-muted">
          未来 14 天（含今日）· 只读，收款在 C 端完成；支付成功后才生成住单，超时未付自动取消并释放笼位预占。
        </span>
      </div>

      {block.state === 'loading' && <p className="mt-2 text-xs text-muted">正在加载待支付托管预约…</p>}
      {block.state === 'error' && (
        <p className="mt-2 flex flex-wrap items-center gap-2 text-xs text-[#E8506B]">
          {block.message}
          <button
            type="button"
            onClick={onRetry}
            className="h-7 rounded-lg bg-white px-2.5 text-[11px] font-bold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue"
          >
            重试
          </button>
        </p>
      )}
      {block.state === 'ready' && rows.length === 0 && (
        <p className="mt-2 text-xs text-muted">未来 14 天内没有未支付的托管预约。</p>
      )}

      {rows.length > 0 && (
        <ul className="mt-1.5 divide-y divide-line">
          {rows.map((r) => (
            <li key={r.id} className="flex flex-wrap items-center gap-x-4 gap-y-1 py-1.5 text-xs">
              <span className="font-bold text-ink">{r.pet?.name ?? '宠物信息缺失'}</span>
              <span className="text-muted">{r.appointmentNo}</span>
              <span className="text-ink">
                {r.customer
                  ? `客户 ${r.customer.nickname ?? '主人未登记'}`
                  : customerVisible
                    ? '客户信息未返回'
                    : '客户信息无权查看'}
                {r.customer?.phoneMasked && <span className="text-muted"> · {r.customer.phoneMasked}</span>}
              </span>
              <span className="text-muted">
                入住 {r.checkinDate} → 离店 {r.checkoutDate}（{r.nights} 晚）
              </span>
              <span className="font-bold text-ink">{fenToYuanText(r.priceSnapshot)}</span>
              <span className="ml-auto flex items-center gap-1 text-muted">
                <IconClock className="h-3 w-3 shrink-0" /> 支付截止 {shortDateTime(r.expireAt)}
              </span>
            </li>
          ))}
        </ul>
      )}
      {atLimit && (
        <p className="mt-1.5 text-[10px] text-muted">
          单次最多返回 {PENDING_BOARDING_LIMIT} 条，超出部分未展示（上方计数为下限）。
        </p>
      )}
    </section>
  )
}

/* ---- 住单卡片：三区（宠物 / 托管信息 / 操作） ---- */

/** 信息格：低对比小标签 + 值。标签跟着值走，因此不再需要跨卡片的六列表头 */
function InfoCell({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <p className="text-[11px] leading-none text-muted">{label}</p>
      <div className="mt-1 min-w-0">{children}</div>
    </div>
  )
}

function StayRow({
  stay,
  open,
  onToggle,
  refreshKey,
  actions,
}: {
  stay: StayItem
  open: boolean
  onToggle: () => void
  refreshKey: number
  actions: RowActions
}) {
  const status = stay.status as StayStatus
  const tone = STATUS_TONE[status] ?? 'gray'
  const pet = stay.pet
  const gender = pet?.gender === 'MALE' ? '♂' : pet?.gender === 'FEMALE' ? '♀' : ''
  const canWriteCare = canCareForStay(actions.caps, actions.myEmployeeId)
  /** 补派/改派沿用入住同一权限码（boarding:checkin），且后端只接受在住态；两者任一不成立就不给按钮 */
  const canAssignCaretaker = actions.caps.checkin && isInStore(stay.status)
  /** 禁用原因走 title；写闸已放宽为「持码本店员工即可写」（主责∨代录，ADR-015 §5 补记 2026-10-27），只剩账号级原因 */
  const careBlockedReason = !actions.caps.careCreate
    ? '当前账号无新增护理记录权限（boarding:care:create）'
    : actions.myEmployeeId === null
      ? '当前账号未绑定员工身份，无法写护理记录'
      : null
  const form = actions.form
  const avatar = mediaSrc(pet?.avatarUrl)
  const customer = stay.customer

  return (
    <div className="overflow-hidden rounded-xl bg-white ring-1 ring-line transition hover:ring-blue/30">
      <div className={CARD_GRID}>
        {/* A · 宠物信息区：确定宽度 230px，头像尺寸统一 */}
        <div className="flex min-w-0 items-center gap-3">
          {avatar ? (
            <img src={avatar} alt={pet?.name ?? ''} className="h-14 w-14 shrink-0 rounded-lg object-cover" />
          ) : (
            <span className="grid h-14 w-14 shrink-0 place-items-center rounded-lg bg-blue-soft text-blue/70">
              <IconPaw className="h-6 w-6" />
            </span>
          )}
          <div className="min-w-0">
            <p className="flex min-w-0 items-center gap-1.5">
              <span className="truncate text-[15px] font-bold leading-5 text-ink" title={pet?.name ?? undefined}>
                {pet?.name ?? '宠物信息缺失'}
              </span>
              {gender && (
                <span className={cx('shrink-0 text-xs font-semibold', pet?.gender === 'MALE' ? 'text-blue' : 'text-[#E8506B]')} title={pet?.gender === 'MALE' ? '公' : '母'}>
                  {gender}
                </span>
              )}
            </p>
            <p className="mt-1 truncate text-xs text-muted" title={pet ? `${pet.species ?? '未登记'} · ${pet.breed ?? '品种未登记'}` : undefined}>
              {pet ? (pet.species
                ? `${SPECIES_LABEL[pet.species as PetSpecies] ?? pet.species} · ${pet.breed ?? '品种未登记'}`
                : '宠物档案已删除')
                : '住单未关联宠物'}
            </p>
          </div>
        </div>

        {/* B · 托管信息区：四格固定顺序，跨卡片起始位置一致 */}
        <div className={cx('min-w-0', INFO_GRID)}>
          <InfoCell label="客户">
            {customer ? (
              <p
                className="truncate text-[13px] font-semibold text-ink"
                title={`${customer.nickname ?? '主人未登记'}${customer.phoneMasked ? ` · ${customer.phoneMasked}` : ''}`}
              >
                {customer.nickname ?? '主人未登记'}
                {customer.phoneMasked && <span className="ml-1 font-normal text-muted">{customer.phoneMasked}</span>}
              </p>
            ) : (
              <p
                className="truncate text-[13px] text-muted"
                title={actions.caps.customer ? '后端未返回客户信息' : '仅持 customer:view:store 的账号可见客户信息'}
              >
                {actions.caps.customer ? '信息未返回' : '无权查看'}
              </p>
            )}
          </InfoCell>

          <InfoCell label="计划入住 → 离店">
            <p className="whitespace-nowrap text-[13px] font-semibold text-ink">
              {stay.checkinDate || '--'} <span className="font-normal text-muted">→</span> {stay.checkoutDate || '--'}
              <span className="ml-1 font-normal text-muted">· {stayNightsText(stay.checkinDate, stay.checkoutDate)}</span>
            </p>
          </InfoCell>

          <InfoCell label="住单状态">
            <span className="inline-block rounded-full px-2.5 py-1 text-[11px] font-bold" style={{ background: TONE[tone].bg, color: TONE[tone].fg }}>
              {STATUS_LABEL[status] ?? stay.status}
            </span>
          </InfoCell>

          <InfoCell label="主责护理员">
            <p className="truncate text-[13px] font-semibold text-ink" title={stay.caretaker?.name ?? undefined}>
              {stay.caretaker?.name ?? '未指派'}
            </p>
            {/* 护理写闸只认 caretaker_id，入住时"暂不指派"的单此前没有补派入口（护理闭环卡死在 70001）。
                入口就放在这一格：它改的是这一格显示的值，放在操作列反而要用户去猜按钮与哪一列有关。 */}
            {canAssignCaretaker && (
              <button
                type="button"
                disabled={actions.busy}
                onClick={() => actions.onOpen('caretaker', stay.id)}
                title="指派/改派主责护理员（护理记录也可由持码员工代录，主责展示以本格为准）"
                className={cx(
                  'mt-1 h-6 rounded-md px-2 text-[11px] font-bold text-ink ring-1 ring-line transition',
                  'hover:text-blue hover:ring-blue disabled:cursor-not-allowed disabled:opacity-50',
                  form?.kind === 'caretaker' && form.stayId === stay.id && 'text-blue ring-blue',
                )}
              >
                {stay.caretaker?.id ? '更换护理员' : '指派护理员'}
              </button>
            )}
          </InfoCell>
        </div>

        {/* C · 操作区：固定 244px 轨道，按钮数量不同也不牵引其他区域 */}
        <div className="col-span-1 flex flex-wrap items-center gap-1.5 border-t border-line pt-3 xl:justify-end xl:border-l xl:border-t-0 xl:pl-4 xl:pt-0">
          <button
            type="button"
            onClick={onToggle}
            aria-expanded={open}
            className="flex h-8 items-center gap-1 rounded-lg bg-white px-2.5 text-xs font-bold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue/40"
          >
            {open ? '收起' : '详情'}
            <IconChevronDown className={cx('h-3.5 w-3.5 transition-transform', open && 'rotate-180')} />
          </button>
          {status === 'EXPECTED' && (
            <button
              type="button"
              disabled={actions.busy || !actions.caps.checkin}
              title={!actions.caps.checkin ? '当前账号无办理入住权限（boarding:checkin）' : undefined}
              onClick={() => actions.onOpen('checkin', stay.id)}
              className={cx(
                'h-8 rounded-lg px-3 text-xs font-bold transition disabled:cursor-not-allowed',
                actions.caps.checkin ? PRIMARY_BTN : 'bg-blue-mist text-muted',
                (actions.busy || !actions.caps.checkin) && 'opacity-60',
              )}
            >
              {form?.kind === 'checkin' && form.stayId === stay.id ? '收起表单' : '办理入住'}
            </button>
          )}
          {isInStore(stay.status) && (
            <>
              <button
                type="button"
                disabled={actions.busy || !canWriteCare}
                title={!canWriteCare ? (careBlockedReason ?? undefined) : undefined}
                onClick={() => actions.onOpen('care', stay.id)}
                className={cx(
                  'h-8 rounded-lg px-3 text-xs font-bold transition disabled:cursor-not-allowed',
                  canWriteCare ? PRIMARY_BTN : 'bg-blue-mist text-muted',
                  (actions.busy || !canWriteCare) && 'opacity-60',
                )}
              >
                {form?.kind === 'care' && form.stayId === stay.id ? '收起表单' : '护理记录'}
              </button>
              <button
                type="button"
                disabled={actions.busy || !actions.caps.checkout}
                title={!actions.caps.checkout ? '当前账号无办理离店权限（boarding:checkout）' : undefined}
                onClick={() => actions.onOpen('checkout', stay.id)}
                className="h-8 rounded-lg bg-white px-3 text-xs font-bold text-ink ring-1 ring-line transition hover:text-[#E8506B] hover:ring-[#E8506B] disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:text-ink disabled:hover:ring-line"
              >
                {form?.kind === 'checkout' && form.stayId === stay.id ? '收起确认' : '办理离店'}
              </button>
            </>
          )}
        </div>
      </div>

      {form && form.stayId === stay.id && form.kind === 'checkin' && (
        <CheckinForm
          stay={stay}
          busy={actions.busy}
          myEmployeeId={actions.myEmployeeId}
          onSubmit={(body) => actions.onCheckin(stay.id, body)}
          onCancel={actions.onClose}
        />
      )}
      {form && form.stayId === stay.id && form.kind === 'care' && (
        <CareForm
          stay={stay}
          busy={actions.busy}
          onSubmit={(body) => actions.onCare(stay.id, body)}
          onCancel={actions.onClose}
        />
      )}
      {form && form.stayId === stay.id && form.kind === 'checkout' && (
        <CheckoutConfirm
          stay={stay}
          busy={actions.busy}
          onConfirm={() => actions.onCheckout(stay.id)}
          onCancel={actions.onClose}
        />
      )}

      {form && form.stayId === stay.id && form.kind === 'caretaker' && (
        <CaretakerForm
          stay={stay}
          busy={actions.busy}
          myEmployeeId={actions.myEmployeeId}
          onSubmit={(caretakerId) => actions.onAssignCaretaker(stay.id, caretakerId)}
          onCancel={actions.onClose}
        />
      )}

      {open && <StayExpand stayId={stay.id} refreshKey={refreshKey} />}
    </div>
  )
}

/** 展开区：GET /{id} 详情 + GET /{id}/care-records（真实字段；失败可重试，写成功后由 refreshKey 重拉） */
function StayExpand({ stayId, refreshKey }: { stayId: string; refreshKey: number }) {
  const [block, setBlock] = useState<Block<{ detail: StayDetail; records: CareRecord[] }>>({ state: 'loading' })
  const [tick, setTick] = useState(0)

  useEffect(() => {
    let alive = true
    setBlock({ state: 'loading' })
    Promise.all([boardingApi.detail(stayId), boardingApi.careRecords(stayId)]).then(
      ([detail, records]) => {
        if (alive) setBlock({ state: 'ready', data: { detail, records } })
      },
      (err: unknown) => {
        if (alive) setBlock({ state: 'error', message: err instanceof BizError ? err.message : '详情加载失败，请重试' })
      },
    )
    return () => {
      alive = false
    }
  }, [stayId, refreshKey, tick])

  const reload = () => setTick((t) => t + 1)

  return (
    <div className="border-t border-line bg-blue-mist/40 px-4 py-3">
      {block.state === 'loading' && <p className="py-3 text-center text-xs text-muted">详情加载中…</p>}
      {block.state === 'error' && (
        <div className="py-3 text-center text-xs">
          <p className="text-[#E8506B]">{block.message}</p>
          <button type="button" onClick={reload} className="mt-2 h-7 rounded-md bg-white px-3 text-[11px] font-bold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue">
            重试
          </button>
        </div>
      )}
      {block.state === 'ready' && (
        <div className="flex flex-col gap-3">
          <div className="grid gap-x-6 gap-y-2 sm:grid-cols-2 xl:grid-cols-3">
            <Field label="住单号" value={block.data.detail.id} />
            <Field label="关联预约 ID" value={block.data.detail.appointmentId || '--'} />
            <Field label="宠物档案 ID" value={block.data.detail.petId} />
            <Field label="实际入住时间" value={shortDateTime(block.data.detail.actualCheckinAt)} />
            <Field label="实际离店时间" value={shortDateTime(block.data.detail.actualCheckoutAt)} />
            <Field label="笼位资源 ID" value={block.data.detail.kennelResourceId ?? '未分配（入住时需指定或沿用预约预占）'} />
            <Field label="特殊说明" value={block.data.detail.specialInstructions ?? '无'} wide />
          </div>

          <div>
            <p className="mb-1.5 flex items-center gap-1.5 text-xs font-bold text-muted">
              <IconHeartPulse className="h-3.5 w-3.5 text-blue" /> 护理记录（{block.data.records.length} 条
              {block.data.records.length >= CARE_RECORD_LIMIT ? '，已达单次上限' : ''}）
            </p>
            {block.data.records.length === 0 ? (
              <p className="text-xs text-muted">该住单暂无护理记录；主责护理员或持护理权限的员工均可填写（ADR-015 §5）。</p>
            ) : (
              <ul className="flex flex-col gap-1.5">
                {block.data.records.map((r) => {
                  const summary = careSummary(r)
                  const photos = r.photoUrls ?? []
                  return (
                    <li key={r.id} className="rounded-lg bg-white p-2.5 ring-1 ring-line">
                      <p className="text-xs font-bold text-ink">
                        {r.careDate}
                        {r.abnormalFlag && (
                          <span className="ml-2 rounded-full px-2 py-0.5 text-[10px] font-semibold" style={{ background: TONE.pink.bg, color: TONE.pink.fg }}>
                            异常
                          </span>
                        )}
                        {photos.length === 0 && (r.photos?.length ?? 0) > 0 && (
                          <span className="ml-2 text-[10px] font-normal text-muted">含 {r.photos?.length} 张照片（暂无读取地址）</span>
                        )}
                      </p>
                      {summary && <p className="mt-1 text-xs leading-relaxed text-muted">{summary}</p>}
                      {r.abnormalFlag && r.abnormalDesc && <p className="mt-1 text-xs text-[#E8506B]">{r.abnormalDesc}</p>}
                      {photos.length > 0 && (
                        <ul className="mt-1.5 flex flex-wrap gap-1.5">
                          {photos.map((url, i) => (
                            <li key={`${r.id}-${i}`}>
                              {/* presigned GET 有效期有限：缩略图直接渲染，点击在新标签打开原图，不引入站内灯箱 */}
                              <a href={url} target="_blank" rel="noreferrer" title={`第 ${i + 1} 张，点击查看原图`}>
                                <img src={url} alt={`护理照片 ${i + 1}`} className="h-14 w-14 rounded-md object-cover ring-1 ring-line transition hover:ring-blue" loading="lazy" />
                              </a>
                            </li>
                          ))}
                        </ul>
                      )}
                    </li>
                  )
                })}
              </ul>
            )}
            {block.data.records.length >= CARE_RECORD_LIMIT && (
              <p className="mt-1.5 text-[10px] text-muted">单次最多返回 {CARE_RECORD_LIMIT} 条护理记录，可能未列全。</p>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

function Field({ label, value, wide }: { label: string; value: string; wide?: boolean }) {
  return (
    <div className={wide ? 'sm:col-span-2 xl:col-span-3' : undefined}>
      <p className="text-[10px] text-muted">{label}</p>
      <p className="mt-0.5 break-all text-xs font-semibold text-ink">{value}</p>
    </div>
  )
}

/* ---- 写侧表单：服务端权威校验，这里只做防呆 + 不可逆动作的二次确认 ----
   护理记录表单（CareForm）与其样式常量已抽到 components/care/CareForm.tsx，托管页与「宠物照顾」工作台共用。 */

function CheckinForm({
  stay,
  busy,
  myEmployeeId,
  onSubmit,
  onCancel,
}: {
  stay: StayItem
  busy: boolean
  myEmployeeId: string | null
  onSubmit: (body: CheckinBody) => void
  onCancel: () => void
}) {
  const [picker, setPicker] = useState<{
    loading: boolean
    kennels: KennelOption[]
    staff: StoreStaff[]
    kennelError: string | null
    staffError: string | null
  }>({ loading: true, kennels: [], staff: [], kennelError: null, staffError: null })
  const [kennelId, setKennelId] = useState('')
  // 默认指派当前操作人（ADR-015 补记 2026-10-27 用户拍板）：住单天然有主责，未指派单不再人人写不了；
  // 想另派或暂不指派仍可手动改。
  const [caretakerId, setCaretakerId] = useState(myEmployeeId ?? '')
  const [notes, setNotes] = useState('')

  useEffect(() => {
    let alive = true
    Promise.allSettled([boardingApi.kennelOptions(stay.id), boardingApi.assignableStaff()]).then(([k, s]) => {
      if (!alive) return
      setPicker({
        loading: false,
        kennels: k.status === 'fulfilled' ? k.value : [],
        staff: s.status === 'fulfilled' ? s.value : [],
        kennelError: k.status === 'fulfilled' ? null : bizMsg(k.reason),
        staffError: s.status === 'fulfilled' ? null : bizMsg(s.reason),
      })
    })
    return () => {
      alive = false
    }
  }, [stay.id])

  const tooLong = notes.length > 500
  const blocked = busy || picker.loading || tooLong

  return (
    <FormShell title={`办理入住 · ${stay.pet?.name ?? '未知宠物'}`}>
      <p className="mt-1 text-[11px] leading-relaxed text-muted">
        实际入住时间由服务端写入；宠物状态/体型、预约状态、笼位类型（猫只进猫舍、犬允许升级禁降级）与并发抢占均由后端权威校验。
        留「沿用预约预占」时不需要笼位清单权限。
      </p>

      {picker.loading && <p className="mt-3 text-xs text-muted">正在拉取本店笼位与在职员工…</p>}

      {!picker.loading && (
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-[11px] text-muted">
            入住笼位
            <select value={kennelId} onChange={(e) => setKennelId(e.target.value)} className={INPUT}>
              <option value="">沿用预约预占笼位（默认）</option>
              {picker.kennels.map((k) => (
                <option key={k.id} value={k.id} disabled={!k.freeForSpan}>
                  {(KENNEL_TYPE_LABEL[k.type as KennelType] ?? k.type) + ' · ' + (k.name || k.id)}
                  {k.freeForSpan ? `（${k.spanFrom} ~ ${k.spanToExclusive} 可用）` : '（该区间已被占用）'}
                </option>
              ))}
            </select>
            {picker.kennelError && (
              <span className="text-[11px] text-[#F59E0B]">
                笼位清单不可用（{picker.kennelError}）——仍可沿用预约预占笼位提交
              </span>
            )}
            {!picker.kennelError && picker.kennels.length === 0 && (
              <span className="text-[11px] text-muted">本店无 AVAILABLE 笼位资源，只能沿用预约预占</span>
            )}
          </label>

          <label className="flex flex-col gap-1 text-[11px] text-muted">
            主责护理员
            <select value={caretakerId} onChange={(e) => setCaretakerId(e.target.value)} className={INPUT}>
              <option value="">暂不指派</option>
              {picker.staff.map((s) => (
                <option key={s.employeeId} value={s.employeeId}>
                  {(s.name || `员工 ${s.employeeId}`) + (s.employeeId === myEmployeeId ? '（我）' : '')}
                  {s.rolesLabel ? ` · ${s.rolesLabel}` : ''}
                </option>
              ))}
              {picker.staffError && myEmployeeId && (
                <option value={myEmployeeId}>由我本人担任（员工名单不可用时的兜底）</option>
              )}
            </select>
            {picker.staffError && (
              <span className="text-[11px] text-[#F59E0B]">
                员工名单不可用（{picker.staffError}）——仅可指派给自己或暂不指派
              </span>
            )}
            {!picker.staffError && picker.staff.length === 0 && (
              <span className="text-[11px] text-muted">本店暂无在职可指派员工</span>
            )}
            <span className="text-[11px] text-muted">
              提示：默认指派给你本人；护理记录由主责或持护理权限的员工填写（店长可代录），不指派也不影响记录录入。
            </span>
          </label>
        </div>
      )}

      <textarea
        value={notes}
        onChange={(e) => setNotes(e.target.value)}
        rows={2}
        placeholder="特殊说明（可选，≤500 字）：喂药、饮食禁忌、性格注意事项…"
        className={cx(AREA, 'mt-3')}
      />
      <div className="mt-1 flex items-center justify-between">
        <span className={cx('text-[11px]', tooLong ? 'text-[#E8506B]' : 'text-muted')}>
          {tooLong ? `已超 ${notes.length - 500} 字，后端会拒绝` : `${notes.length}/500`}
        </span>
        <div className="flex gap-2">
          <button type="button" onClick={onCancel} className="h-9 rounded-lg bg-white px-4 text-xs font-bold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue">
            取消
          </button>
          <button
            type="button"
            disabled={blocked}
            onClick={() =>
              onSubmit({
                kennelResourceId: kennelId || null,
                caretakerId: caretakerId || null,
                specialInstructions: notes.trim() || null,
              })
            }
            className={cx('h-9 rounded-lg px-4 text-xs font-bold transition', NAVY, blocked && 'opacity-60')}
          >
            {busy ? '提交中…' : '确认入住'}
          </button>
        </div>
      </div>
    </FormShell>
  )
}

/**
 * 补派/改派主责护理员（POST /{id}/caretaker）。候选沿用入住时同一份名单
 * （GET /store/boarding/assignable-staff，权限码与写侧端点同为 boarding:checkin），
 * 只提交 caretakerId 一个字段——状态/笼位/日期都不在改动范围内。
 * 名单拉不到时仍可选自己（与入住表单同款兜底，服务端照旧按"本店有效员工"复核）。
 */
function CaretakerForm({
  stay,
  busy,
  myEmployeeId,
  onSubmit,
  onCancel,
}: {
  stay: StayItem
  busy: boolean
  myEmployeeId: string | null
  onSubmit: (caretakerId: string) => void
  onCancel: () => void
}) {
  const current = stay.caretaker?.id ?? ''
  const [staff, setStaff] = useState<StoreStaff[]>([])
  const [staffError, setStaffError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [caretakerId, setCaretakerId] = useState(current)

  useEffect(() => {
    let alive = true
    boardingApi
      .assignableStaff()
      .then(
        (v) => {
          if (!alive) return
          setStaff(v)
          setStaffError(null)
        },
        (err: unknown) => {
          if (!alive) return
          setStaff([])
          setStaffError(bizMsg(err))
        },
      )
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [stay.id])

  const blocked = busy || loading || !caretakerId || caretakerId === current

  return (
    <FormShell title={`${current ? '更换' : '指派'}护理员 · ${stay.pet?.name ?? '未知宠物'}`}>
      <p className="mt-1 text-[11px] leading-relaxed text-muted">
        主责护理员是该单照护的第一责任人；护理记录也可由持护理权限的员工代录（ADR-015 §5 补记 2026-10-27）。
        改派只切换<b className="text-ink">此后</b>的主责归属，已写记录与其署名的护理员都不回溯修改。
      </p>

      <div className="mt-3 grid gap-3 sm:grid-cols-2">
        <label className="flex flex-col gap-1 text-[11px] text-muted">
          主责护理员
          <select value={caretakerId} onChange={(e) => setCaretakerId(e.target.value)} className={INPUT}>
            <option value="">{loading ? '正在拉取本店在职员工…' : '请选择护理员'}</option>
            {staff.map((s) => (
              <option key={s.employeeId} value={s.employeeId}>
                {(s.name || `员工 ${s.employeeId}`) +
                  (s.employeeId === myEmployeeId ? '（我）' : '') +
                  (s.employeeId === current ? '（当前）' : '')}
                {s.rolesLabel ? ` · ${s.rolesLabel}` : ''}
              </option>
            ))}
            {staffError && myEmployeeId && myEmployeeId !== current && (
              <option value={myEmployeeId}>由我本人担任（员工名单不可用时的兜底）</option>
            )}
          </select>
          {staffError && (
            <span className="text-[11px] text-[#F59E0B]">
              员工名单不可用（{staffError}）——仅可指派给自己
            </span>
          )}
          {!staffError && !loading && staff.length === 0 && (
            <span className="text-[11px] text-muted">本店暂无在职可指派员工</span>
          )}
        </label>
        <p className="self-end text-[11px] leading-relaxed text-muted">
          当前：{stay.caretaker?.name ?? '未指派'}。主责是本单照护第一责任人（展示与追溯口径），护理记录的写入资格为「主责 ∨ 持护理权限员工」。
        </p>
      </div>

      <div className="mt-3 flex items-center justify-between gap-3">
        <span className="text-[11px] text-muted">
          {!caretakerId ? '请选择护理员' : caretakerId === current ? '与当前一致，无需重复指派' : '将改派所选员工'}
        </span>
        <div className="flex gap-2">
          <button type="button" onClick={onCancel} className="h-9 rounded-lg bg-white px-4 text-xs font-bold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue">
            取消
          </button>
          <button
            type="button"
            disabled={blocked}
            onClick={() => onSubmit(caretakerId)}
            className={cx('h-9 rounded-lg px-4 text-xs font-bold transition', NAVY, blocked && 'opacity-60')}
          >
            {busy ? '提交中…' : current ? '确认改派' : '确认指派'}
          </button>
        </div>
      </div>
    </FormShell>
  )
}

function CheckoutConfirm({
  stay,
  busy,
  onConfirm,
  onCancel,
}: {
  stay: StayItem
  busy: boolean
  onConfirm: () => void
  onCancel: () => void
}) {
  // 在住单的关联预约应为 SERVING；漂移（夹具/异常数据）时离店 CAS 必被打不动（50003），
  // 与其让用户点了吃 raw 报错，不如先禁用并说明。旧镜像无 appointmentStatus（undefined）→ 不拦。
  const aptMismatch =
    isInStore(stay.status) && stay.appointmentStatus != null && stay.appointmentStatus !== 'SERVING'
  return (
    <FormShell title={`确认办理离店 · ${stay.pet?.name ?? '未知宠物'}`}>
      <p className="mt-1 text-[11px] leading-relaxed text-muted">
        离店会写入实际离店时间（服务端时间）、把预约置为已完成，并
        <b className="text-ink">释放今日之后到计划离店日的笼位占用</b>；此操作不可撤销。
        计划离店日 {stay.checkoutDate}。
      </p>
      {aptMismatch && stay.appointmentStatus != null && (
        <p className="mt-2 rounded-lg bg-[#FFF4F5] px-3 py-2 text-[11px] leading-relaxed text-[#B4424F] ring-1 ring-[#F3D3D8]">
          该单关联预约当前为「{APT_STATUS_LABEL[stay.appointmentStatus] ?? stay.appointmentStatus}」，与在住状态
          （应为服务中）不一致，暂不可办理离店——多为数据漂移，请联系管理员核对后刷新重试。
        </p>
      )}
      <div className="mt-3 flex justify-end gap-2">
        <button type="button" onClick={onCancel} className="h-9 rounded-lg bg-white px-4 text-xs font-bold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue">
          取消
        </button>
        <button
          type="button"
          disabled={busy || aptMismatch}
          onClick={onConfirm}
          className={cx('h-9 rounded-lg px-4 text-xs font-bold text-white transition', busy || aptMismatch ? 'bg-[#F3A9B4]' : 'bg-[#E8506B] hover:bg-[#D33F5B]')}
        >
          {busy ? '提交中…' : '确认离店'}
        </button>
      </div>
    </FormShell>
  )
}

/* ---- 右栏 ---- */

function StatsCard({
  block,
  onRetry,
  keywordActive,
}: {
  block: Block<StayStatusCounts>
  onRetry: () => void
  keywordActive: boolean
}) {
  const boxes: { label: string; key: BoardingTab; Icon: typeof IconHouse; tone: Tone }[] = [
    { label: '在店中', key: 'IN_STORE', Icon: IconHouse, tone: 'blue' },
    { label: '待入住', key: 'EXPECTED', Icon: IconClock, tone: 'orange' },
    { label: '已离店', key: 'CHECKED_OUT', Icon: IconCheckCircle, tone: 'green' },
    { label: '异常', key: 'ABNORMAL', Icon: IconXCircle, tone: 'pink' },
  ]
  const pick = (key: BoardingTab) => {
    const tab = STATUS_TABS.find((t) => t.key === key)
    if (!tab || block.state !== 'ready') return '--'
    return Number(tab.pick(block.data))
  }
  return (
    <section className="rounded-2xl bg-white p-4 shadow-soft ring-1 ring-line">
      <h2 className="text-sm font-bold text-ink">托管服务数据</h2>
      {block.state === 'loading' && <p className="mt-3 text-center text-xs text-muted">统计中…</p>}
      {block.state === 'error' && (
        <div className="mt-3 text-center">
          <p className="text-xs text-[#E8506B]">{block.message}</p>
          <button type="button" onClick={onRetry} className="mt-2 text-xs text-blue hover:underline">
            重试
          </button>
        </div>
      )}
      {block.state === 'ready' && (
        <div className="mt-3 grid grid-cols-4 gap-1.5">
          {boxes.map(({ label, key, Icon, tone }) => (
            <div key={key} className="flex flex-col items-center gap-1 rounded-lg py-2.5" style={{ background: TONE[tone].bg }}>
              <span className="grid h-6 w-6 place-items-center rounded-full bg-white" style={{ color: TONE[tone].fg }}>
                <Icon className="h-3.5 w-3.5" />
              </span>
              <p className="text-base font-black leading-none text-ink">{pick(key)}</p>
              <p className="text-[10px] text-muted">{label}</p>
            </div>
          ))}
        </div>
      )}
      <p className="mt-2 text-[10px] leading-relaxed text-muted">
        服务端按本店全部住单统计（与页签角标同一份计数）
        {keywordActive ? '；当前含关键词过滤' : ''}。
      </p>
    </section>
  )
}

/** 笼位容量需 boarding:capacity:view，与列表权限码不同 → 本块可单独 10003/无权限，不影响其他区块 */
function CapacityCard({
  block,
  onRetry,
  enabled,
}: {
  block: Block<KennelCapacity[]>
  onRetry: () => void
  enabled: boolean
}) {
  return (
    <section className="rounded-2xl bg-white p-4 shadow-soft ring-1 ring-line">
      <h2 className="text-sm font-bold text-ink">今日笼位占用</h2>
      {!enabled ? (
        <p className="mt-3 text-xs leading-relaxed text-muted">
          当前账号无笼位容量查看权限（boarding:capacity:view）。
        </p>
      ) : (
        <>
          {block.state === 'loading' && <p className="mt-3 text-center text-xs text-muted">查询中…</p>}
          {block.state === 'error' && (
            <div className="mt-3 text-center">
              <p className="text-xs text-[#E8506B]">{block.message}</p>
              <button type="button" onClick={onRetry} className="mt-2 text-xs text-blue hover:underline">
                重试
              </button>
            </div>
          )}
          {block.state === 'ready' && block.data.length === 0 && (
            <p className="mt-3 text-xs leading-relaxed text-muted">本店尚未配置笼位资源，暂无容量数据。</p>
          )}
          {block.state === 'ready' && block.data.length > 0 && (
            <ul className="mt-3 flex flex-col gap-2.5">
              {block.data.map((c) => {
                const total = Number(c.total)
                const occupied = Number(c.occupied)
                const pct = Number.isFinite(total) && total > 0 ? Math.min(100, Math.round((occupied / total) * 100)) : 0
                return (
                  <li key={c.type}>
                    <div className="flex items-center justify-between text-xs">
                      <span className="font-semibold text-ink">{KENNEL_TYPE_LABEL[c.type as KennelType] ?? c.type}</span>
                      <span className="text-muted">
                        占用 {c.occupied}/{c.total}
                      </span>
                    </div>
                    <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-blue-mist">
                      <i className="block h-full rounded-full bg-blue" style={{ width: `${pct}%` }} />
                    </div>
                    <p className="mt-0.5 text-[10px] text-muted">可用 {c.available}</p>
                  </li>
                )
              })}
            </ul>
          )}
        </>
      )}
    </section>
  )
}

/**
 * 新增托管面板（4.4）。真实语义：`boa_boarding_stay.appointment_id NOT NULL + UNIQUE(uk_appt)`
 * → 住单只能挂在预约上，正常路径是支付事件链自动预生成（ADR-016），本入口只做「漏单补建」。
 * POST 无请求体：宠物/日期/门店全部由后端取预约快照，前端没有可注入面；
 * 候选读 `/store/appointments`（appointment:view:store），补建写需 boarding:checkin，越权由后端 10003 拒绝。
 */
function ProvisionPanel({
  block,
  existingAppointmentIds,
  busyId,
  onProvision,
  onRetry,
  onClose,
}: {
  block: Block<PageData<AppointmentItem>>
  existingAppointmentIds: Set<string>
  busyId: string | null
  onProvision: (appointmentId: string) => void
  onRetry: () => void
  onClose: () => void
}) {
  const total = block.state === 'ready' ? Number(block.data.total) : 0
  const truncated = block.state === 'ready' && Number.isFinite(total) && total > block.data.list.length
  return (
    <section className="rounded-2xl bg-white p-4 shadow-soft ring-1 ring-line">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="text-sm font-bold text-ink">新增托管 · 从已确认预约补建「待入住」单</h2>
          <p className="mt-1 text-[11px] leading-relaxed text-muted">
            住单与预约一对一绑定，这里只补建待入住单，不创建预约、不修改宠物与日期。
            需同时具备办理入住与门店预约查看权限（店长/前台/平台管理员）。
          </p>
        </div>
        <button type="button" onClick={onClose} className="shrink-0 rounded-md px-2 py-1 text-[11px] text-muted transition hover:bg-blue-mist hover:text-ink">
          收起
        </button>
      </div>

      <div className="mt-2 flex flex-col gap-1.5">
        {block.state === 'loading' && <p className="py-3 text-center text-xs text-muted">正在拉取本店已确认预约…</p>}
        {block.state === 'error' && (
          <div className="py-2 text-center">
            <p className="text-xs text-[#E8506B]">{block.message}</p>
            <button type="button" onClick={onRetry} className="mt-1.5 text-xs text-blue hover:underline">
              重试
            </button>
          </div>
        )}
        {block.state === 'ready' && block.data.list.length === 0 && (
          <p className="py-3 text-center text-xs leading-relaxed text-muted">
            本店今日没有状态为「已确认」的预约。顾客支付成功后住单会自动出现在「待入住」，无需在此补建。
          </p>
        )}
        {block.state === 'ready' &&
          block.data.list.map((a) => {
            const done = existingAppointmentIds.has(a.id)
            return (
              <div key={a.id} className="flex flex-wrap items-center gap-x-4 gap-y-1.5 rounded-lg bg-blue-mist/50 px-3 py-2 ring-1 ring-line">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-xs font-bold text-ink">
                    {a.serviceName}
                    <span className="ml-2 text-[11px] font-normal text-muted">{a.appointmentNo}</span>
                  </p>
                  <p className="truncate text-[11px] text-muted">
                    {a.pet?.name ?? '宠物信息缺失'} · {a.serviceDate}
                    {a.startTime ? ` ${a.startTime}` : ''} · {a.status}
                  </p>
                </div>
                {done ? (
                  <span className="shrink-0 rounded-full px-2.5 py-1 text-[11px] font-semibold" style={{ background: TONE.gray.bg, color: TONE.gray.fg }}>
                    已有住单
                  </span>
                ) : (
                  <button
                    type="button"
                    disabled={busyId !== null}
                    onClick={() => onProvision(a.id)}
                    className={cx(
                      'h-8 shrink-0 rounded-lg px-3 text-[11px] font-bold transition',
                      busyId === a.id ? 'bg-blue-mist text-muted' : NAVY,
                      busyId !== null && busyId !== a.id && 'opacity-60',
                    )}
                  >
                    {busyId === a.id ? '生成中…' : '生成待入住单'}
                  </button>
                )}
              </div>
            )
          })}
        {block.state === 'ready' && block.data.list.length > 0 && (
          <p className="mt-0.5 text-[10px] text-muted">
            候选=本店今日「已确认」预约第一页（{block.data.list.length} 条
            {truncated ? `，共 ${block.data.total} 条` : ''}）；非跨天托管预约由后端拒绝并说明原因。
            「已有住单」只按**当前页**的住单判定，重复补建由服务端幂等兜底（返回既有住单，不会产生第二条）。
          </p>
        )}
      </div>
    </section>
  )
}

/**
 * 今日护理动态（ADR-032）：全店聚合端点 `GET /store/boarding/care-activity` 一次取回。
 * 后端按业务日与权限范围收窄（护理员只看自己被指派的单），前端不判断；
 * 单次最多 100 条 → 触顶时必须说「可能更多」，不能把截断后的条数说成全量。
 * 独立成块降级：这张卡拉不到，不影响住单列表与明细。
 */
function TodayActivity({ storeId, refreshKey }: { storeId: string | null; refreshKey: number }) {
  const { block, reload } = useAsync(
    () => boardingApi.careActivity(),
    storeId ? `${storeId}|${refreshKey}` : null,
  )
  const rows = block.state === 'ready' ? block.data : []
  const atLimit = rows.length >= CARE_ACTIVITY_LIMIT
  return (
    <section className="rounded-2xl bg-white p-4 shadow-soft ring-1 ring-line">
      <h2 className="text-sm font-bold text-ink">今日护理动态</h2>
      {block.state === 'loading' && <p className="py-3 text-center text-xs text-muted">加载中…</p>}
      {block.state === 'error' && (
        <div className="py-3 text-center text-xs">
          <p className="leading-relaxed text-muted">本卡暂不可用：{block.message}</p>
          <p className="mt-0.5 text-[10px] text-muted">不影响住单列表与单条住单的护理记录明细</p>
          <button type="button" onClick={reload} className="mt-2 h-7 rounded-md bg-white px-3 text-[11px] font-bold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue">
            重试
          </button>
        </div>
      )}
      {block.state === 'ready' && rows.length === 0 && (
        <p className="py-3 text-center text-xs leading-relaxed text-muted">
          今日暂无护理记录（主责或持护理权限员工可填写，ADR-015 §5）。
        </p>
      )}
      {block.state === 'ready' && rows.length > 0 && (
        <>
          <ul className="mt-1 divide-y divide-line">
            {rows.slice(0, ACTIVITY_PREVIEW_LIMIT).map((e) => {
              const summary = careSummary(e)
              return (
                <li key={e.recordId} className="py-2">
                  <p className="text-xs font-bold text-ink">
                    {e.petName ?? '未命名宠物'}
                    <span className="ml-1.5 text-[11px] font-normal text-muted">{e.careDate}</span>
                    {e.abnormalFlag && (
                      <span className="ml-1.5 rounded-full px-1.5 py-0.5 text-[10px] font-semibold" style={{ background: TONE.pink.bg, color: TONE.pink.fg }}>
                        异常
                      </span>
                    )}
                  </p>
                  <p className="mt-0.5 text-[11px] leading-snug text-muted">
                    {summary ?? '已记录，无明细字段'}
                    {e.caretakerName && <span className="ml-1">· {e.caretakerName}</span>}
                  </p>
                </li>
              )
            })}
          </ul>
          <p className="mt-2 text-[10px] leading-relaxed text-muted">
            {atLimit
              ? `当前账号可见范围内今日已达单次上限 ${CARE_ACTIVITY_LIMIT} 条（可能还有更多）；仅列前 ${ACTIVITY_PREVIEW_LIMIT} 条，最新在前。`
              : `当前账号可见范围内今日共 ${rows.length} 条记录，最新在前${rows.length > ACTIVITY_PREVIEW_LIMIT ? `（仅列前 ${ACTIVITY_PREVIEW_LIMIT} 条）` : ''}。`}
          </p>
        </>
      )}
    </section>
  )
}

function TipsCard() {
  return (
    <section className="relative overflow-hidden rounded-2xl bg-gradient-to-br from-[#FFF8EC] to-white p-4 shadow-soft ring-1 ring-line">
      <h2 className="flex items-center gap-2 text-sm font-bold text-ink">
        <span className="grid h-6 w-6 place-items-center rounded-full bg-[#FFF1E0] text-[#F59E0B]">
          <IconBulb className="h-3.5 w-3.5" />
        </span>
        托管小贴士
      </h2>
      <ul className="mt-3 flex flex-col gap-2">
        {TIPS.map((t) => (
          <li key={t} className="flex items-start gap-2 text-xs leading-snug text-ink">
            <IconCheckCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-[#2FA96B]" /> {t}
          </li>
        ))}
      </ul>
      <p className="mt-2 flex items-center gap-1 text-[10px] text-muted">
        静态提示文案 <IconChevronRight className="h-3 w-3" /> 非门店数据
      </p>
    </section>
  )
}
