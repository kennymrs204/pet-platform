import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useSearchParams } from 'react-router-dom'
import Card from '../components/ui/Card'
import Layout from '../components/layout/Layout'
import Modal from '../components/ui/Modal'
import {
  IconBulb,
  IconChevronDown,
  IconClipboard,
  IconClock,
  IconShield,
  IconUsers,
  IconWallet,
} from '../components/ui/icons'
import { useToast } from '../components/ui/Toast'
import { storeContext } from '../auth/storeContext'
import { tokenStore } from '../auth/tokenStore'
import { BizError } from '../api/types'
import { fenToYuanText, type PageData } from '../api/dashboard'
import {
  ORDER_STATUSES,
  ORDER_STATUS_LABEL,
  PAYMENT_METHOD_LABEL,
  localDate,
  orderTimeText,
  ordersApi,
  pagePaidSum,
  exportOrdersCsv,
  type OrderItem,
  type OrderStatus,
} from '../api/orders'
import { cx } from '../lib/cx'
import { TONE } from '../lib/tone'
import { canApproveRefund, REFUND_STATUS_LABEL, refundsApi, type RefundItem } from '../api/refunds'
import type { Tone } from '../mock/dashboard'

const NAVY = 'bg-[#1E3A5F] text-white hover:bg-[#16304F]'
const PAGE_SIZE = 20

type Tab = 'ALL' | OrderStatus

/**
 * 只认本页真能落实的两个参数；页签必须是已知状态，否则回 ALL（不猜、不报错、不白屏）。
 * openId 是字符串化的雪花 id，只和 `OrderItem.id` 比，不做数字解析（19 位会超安全整数）。
 */
function readOrdersDeepLink(params: URLSearchParams): { tab?: Tab; openId?: string } | null {
  const rawTab = params.get('tab')
  const tab = rawTab && (ORDER_STATUSES as string[]).includes(rawTab) ? (rawTab as Tab) : undefined
  const openId = params.get('openId') || undefined
  return tab || openId ? { tab, openId } : null
}

/**
 * B 端订单管理页（ADR-019 整块：后端已实现，本页直连真实接口，无演示数据）。
 * 状态页签、日期区间、订单号前缀搜索都走服务端参数（不是前端过滤）；分页用真实 total。
 * 退款审批已接真实接口（ADR-034，见页内 RefundPanel）；线下收款登记/复核已接真实接口
 * （ADR-006 §6，双人原则）；导出对账单已接真实 CSV 端点（打通轮 P5，同筛选参数、无分页）。
 */
export default function OrdersPage() {
  const { push } = useToast()
  const notReady = (label: string) => () => push(`${label}：功能暂未接入`, 'info')
  const ctx = useSyncExternalStore(storeContext.subscribe, storeContext.get)
  const storeId = ctx.storeId
  const [searchParams] = useSearchParams()

  const [tab, setTab] = useState<Tab>('ALL')
  const [dateFrom, setDateFrom] = useState('')
  const [dateTo, setDateTo] = useState('')
  const [keywordInput, setKeywordInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [pageNum, setPageNum] = useState(1)
  const [openId, setOpenId] = useState<string | null>(null)

  // 任何筛选变化都回到第 1 页，否则可能停在越界页码上显示空列表
  useEffect(() => {
    setPageNum(1)
    setOpenId(null)
  }, [storeId, tab, dateFrom, dateTo, keyword])

  const list = useAsync(
    () =>
      ordersApi.list({
        status: tab === 'ALL' ? undefined : tab,
        dateFrom: dateFrom || undefined,
        dateTo: dateTo || undefined,
        keyword: keyword || undefined,
        pageNum,
        pageSize: PAGE_SIZE,
      }),
    `${storeId}|${tab}|${dateFrom}|${dateTo}|${keyword}|${pageNum}`,
  )

  // 提交时才落 keyword（<2 字符后端会 10001，前端提前拦给即时反馈）
  const applyKeyword = () => {
    const kw = keywordInput.trim()
    if (kw.length === 1) {
      push('订单号搜索至少输入 2 个字符', 'error')
      return
    }
    setKeyword(kw)
  }

  const page = list.block.state === 'ready' ? list.block.data : null
  const rows = page?.list ?? []
  const total = page ? Number(BigInt(page.total)) : 0
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  /** 导出对账单（P5）：与列表同一组筛选参数（不带分页），blob 落地为文件；失败信封走 toast。 */
  const [exporting, setExporting] = useState(false)
  const handleExport = async () => {
    if (exporting) return
    setExporting(true)
    try {
      const blob = await exportOrdersCsv({
        status: tab === 'ALL' ? undefined : (tab as OrderStatus),
        dateFrom: dateFrom || undefined,
        dateTo: dateTo || undefined,
        keyword: keyword || undefined,
      })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `对账单_${localDate()}.csv`
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
      push('对账单已导出（按当前筛选条件，含客户脱敏手机号）', 'success')
    } catch (err) {
      push(err instanceof BizError ? err.message : '导出失败，请重试', 'error')
    } finally {
      setExporting(false)
    }
  }

  /**
   * 消息中心深链（ADR-047）：`?tab=REFUNDING` 切页签（"有退款待处理"跳的是**队列**——
   * 那条消息的 bizId 是退款单 id，而本页的展开键是订单 id，两者不通，所以跳筛选而不是跳一行）；
   * `?openId={订单 id}` 展开那一行（新预约待服务用）。
   *
   * 刻意等列表 ready 才应用，而不是拿来当 useState 初值：mount 时门店上下文还在加载
   * （storeId 会 null→9111 变一次），上面那个"筛选变化就收起展开行"的 effect 会在变化时把
   * openId 抹掉；在 ready 之后应用才跑在它后面。定位是尽力而为——那一行不在当前页就是不高亮，
   * 页签已经替用户把范围缩对了。
   */
  const pendingLink = useRef(readOrdersDeepLink(searchParams))
  useEffect(() => {
    const link = pendingLink.current
    if (!storeId || !link || list.block.state !== 'ready') return
    pendingLink.current = null
    if (link.tab) setTab(link.tab)
    if (link.openId) setOpenId(link.openId)
  }, [storeId, list.block.state])

  // 待处理退款：与订单列表独立加载（任务角色看不到订单，但要能看到退款进度，ADR-034 §3）。
  // 固定 3 条/页（本单设计：面板常驻 3 个待处理位，多了走分页；换店回第 1 页防越界空页）。
  const [refundPage, setRefundPage] = useState(1)
  useEffect(() => {
    setRefundPage(1)
  }, [storeId])
  const refunds = useAsync(() => refundsApi.page('APPLYING', refundPage, 3), `${storeId}|refunds|p${refundPage}`)
  const canApprove = canApproveRefund(ctx.identity?.stores.find((s) => s.storeId === storeId)?.roles ?? [])

  const tabs: Tab[] = ['ALL', ...ORDER_STATUSES]

  return (
    <Layout active="orders">
      <div className="grid gap-5 p-4 sm:p-6 2xl:grid-cols-[minmax(0,1fr)_320px]">
        <div className="flex min-w-0 flex-col gap-5">
          <RefundPanel
            block={refunds.block}
            reload={refunds.reload}
            canApprove={canApprove}
            page={refundPage}
            onPageChange={setRefundPage}
          />
          <div className="rounded-2xl bg-white p-5 shadow-soft ring-1 ring-line sm:p-6">
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div>
                <h1 className="text-[26px] font-black text-ink">订单管理</h1>
                <p className="mt-1.5 text-sm text-muted">本店全部订单与实收情况（只读；金额按创建时快照，不随改价变动）</p>
              </div>
              <button type="button" disabled={exporting} onClick={() => void handleExport()} className="flex h-11 items-center gap-2 rounded-full bg-white px-5 text-sm font-bold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue disabled:opacity-50">
                <IconClipboard className="h-4 w-4" /> {exporting ? '导出中…' : '导出对账单'}
              </button>
            </div>

            <div className="mt-5 flex flex-wrap gap-2.5" role="tablist" aria-label="订单状态">
              {tabs.map((key) => (
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
                  {key === 'ALL' ? '全部' : ORDER_STATUS_LABEL[key]}
                </button>
              ))}
            </div>

            <div className="mt-4 flex flex-wrap items-center gap-2.5">
              <label className="flex items-center gap-2 text-xs text-muted">
                下单日期
                <input
                  type="date"
                  value={dateFrom}
                  max={dateTo || undefined}
                  onChange={(e) => setDateFrom(e.target.value)}
                  aria-label="起始日期"
                  className="h-9 rounded-lg bg-white px-2.5 text-sm text-ink ring-1 ring-line focus:outline-none focus:ring-blue"
                />
                至
                <input
                  type="date"
                  value={dateTo}
                  min={dateFrom || undefined}
                  onChange={(e) => setDateTo(e.target.value)}
                  aria-label="结束日期"
                  className="h-9 rounded-lg bg-white px-2.5 text-sm text-ink ring-1 ring-line focus:outline-none focus:ring-blue"
                />
              </label>
              <button type="button" onClick={() => { setDateFrom(localDate()); setDateTo(localDate()) }} className="h-9 rounded-full bg-white px-3.5 text-xs font-semibold text-ink ring-1 ring-line hover:text-blue hover:ring-blue">今天</button>
              <button type="button" onClick={() => { setDateFrom(localDate(-6)); setDateTo(localDate()) }} className="h-9 rounded-full bg-white px-3.5 text-xs font-semibold text-ink ring-1 ring-line hover:text-blue hover:ring-blue">近 7 天</button>
              {(dateFrom || dateTo) && (
                <button type="button" onClick={() => { setDateFrom(''); setDateTo('') }} className="h-9 rounded-full bg-white px-3.5 text-xs font-semibold text-muted ring-1 ring-line hover:text-ink">
                  清空日期
                </button>
              )}
              <label className="flex items-center gap-2 text-xs text-muted">
                订单号
                <input
                  type="text"
                  value={keywordInput}
                  maxLength={40}
                  placeholder="前缀搜索，如 PO2026"
                  aria-label="订单号前缀"
                  onChange={(e) => setKeywordInput(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter') applyKeyword() }}
                  className="h-9 w-44 rounded-lg bg-white px-2.5 text-sm text-ink ring-1 ring-line focus:outline-none focus:ring-blue"
                />
              </label>
              <button type="button" onClick={applyKeyword} className="h-9 rounded-full bg-blue px-3.5 text-xs font-semibold text-white shadow-soft transition hover:bg-blue/90">搜索</button>
              {(keyword || keywordInput) && (
                <button type="button" onClick={() => { setKeywordInput(''); setKeyword('') }} className="h-9 rounded-full bg-white px-3.5 text-xs font-semibold text-muted ring-1 ring-line hover:text-ink">
                  清空搜索
                </button>
              )}
              <p className="ml-auto text-xs text-muted">区间含首尾两天（按下单时间）；订单号按前缀匹配，可与页签/日期组合</p>
            </div>

            <div className="mt-5 flex flex-col gap-3">
              {list.block.state === 'loading' && <ListLoading />}
              {list.block.state === 'error' && <ListError message={list.block.message} onRetry={list.reload} />}
              {page && rows.length === 0 && (
                <p className="rounded-2xl bg-blue-mist/60 py-12 text-center text-sm text-muted">该条件下暂无订单</p>
              )}
              {rows.map((o) => (
                <OrderBlock key={o.id} order={o} open={openId === o.id} onToggle={() => setOpenId((v) => (v === o.id ? null : o.id))} />
              ))}
              {page && totalPages > 1 && (
                <div className="flex items-center justify-between gap-3 pt-1">
                  <p className="text-xs text-muted">共 {total} 条 · 第 {page.pageNum}/{totalPages} 页</p>
                  <div className="flex gap-2">
                    <button
                      type="button"
                      disabled={pageNum <= 1}
                      onClick={() => setPageNum((n) => Math.max(1, n - 1))}
                      className="h-9 rounded-lg bg-white px-4 text-xs font-semibold text-ink ring-1 ring-line disabled:text-muted disabled:opacity-50"
                    >
                      上一页
                    </button>
                    <button
                      type="button"
                      disabled={pageNum >= totalPages}
                      onClick={() => setPageNum((n) => n + 1)}
                      className={cx('h-9 rounded-lg px-4 text-xs font-bold disabled:opacity-50', NAVY)}
                    >
                      下一页
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>

        <div className="flex min-w-0 flex-col gap-5">
          <RangeCard rows={rows} total={total} loading={list.block.state === 'loading'} tab={tab} />
          <BoundaryCard onNotReady={notReady} />
          <NoteCard />
        </div>
      </div>
    </Layout>
  )
}

/* ---- 区块数据（与首页/宠物页/客户页/员工页同模式） ---- */

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

/* ---- 待处理退款（ADR-034 §3/§4：进度全员可见，审批仅店长/平台管理员） ---- */

function RefundPanel({ block, reload, canApprove, page, onPageChange }: {
  block: Block<PageData<RefundItem>>
  reload: () => void
  canApprove: boolean
  page: number
  onPageChange: (page: number) => void
}) {
  const { push } = useToast()
  const [busyId, setBusyId] = useState<string | null>(null)
  const [rejectTarget, setRejectTarget] = useState<RefundItem | null>(null)
  const [rejectReason, setRejectReason] = useState('')
  const [rejectError, setRejectError] = useState('')
  const items = block.state === 'ready' ? block.data.list : []
  const total = block.state === 'ready' ? Number(block.data.total) : 0
  const totalPages = Math.max(1, Math.ceil(total / 3))

  // 处理掉当前页最后一条（或翻页越界）后回退一页，避免停在空页上
  useEffect(() => {
    if (block.state === 'ready' && items.length === 0 && page > 1) onPageChange(page - 1)
  }, [block.state, items.length, page, onPageChange])

  const act = async (r: RefundItem, kind: 'approve' | 'reject') => {
    if (kind === 'reject') {
      setRejectTarget(r)
      setRejectReason('')
      setRejectError('')
      return
    }
    setBusyId(r.id)
    try {
      const done = await refundsApi.approve(r.id)
      push(`退款单 ${done.refundNo} 已通过：${fenToYuanText(r.amount)} 记为已退款（当前为模拟执行，未走真实渠道）`)
      reload()
    } catch (err) {
      push(err instanceof BizError ? err.message : '操作失败，请重试', 'error')
    } finally {
      setBusyId(null)
    }
  }

  const confirmReject = async () => {
    if (!rejectTarget) return
    const reason = rejectReason.trim()
    if (!reason) {
      setRejectError('请填写拒绝理由，顾客会在退款进度中看到')
      return
    }
    if (reason.length > 200) {
      setRejectError('拒绝理由不能超过 200 字')
      return
    }
    setBusyId(rejectTarget.id)
    try {
      const done = await refundsApi.reject(rejectTarget.id, reason)
      push(`退款单 ${done.refundNo} 已拒绝：订单回到「已支付」，预约恢复「已确认」可继续服务`)
      setRejectTarget(null)
      reload()
    } catch (err) {
      setRejectError(err instanceof BizError ? err.message : '操作失败，请重试')
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="rounded-2xl bg-white p-5 shadow-soft ring-1 ring-line">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="flex items-center gap-2 text-sm font-bold text-ink">
          <IconShield className="h-4 w-4 text-blue" /> 待处理退款
          {block.state === 'ready' && (
            <span
              className={cx(
                'rounded-full px-2 py-0.5 text-[11px] font-semibold',
                total ? 'bg-[#FFE9EE] text-[#E8506B]' : 'bg-[#E0F5E9] text-[#2FA96B]',
              )}
            >
              {total ? `${total} 笔` : '无'}
            </span>
          )}
        </p>
        <button type="button" onClick={reload} className="h-8 rounded-full px-3 text-xs font-semibold text-muted ring-1 ring-line transition hover:text-blue hover:ring-blue">
          刷新
        </button>
      </div>

      {block.state === 'loading' && <p className="mt-3 text-xs text-muted">正在读取退款申请…</p>}
      {block.state === 'error' && (
        <p className="mt-3 flex items-center gap-2 text-xs text-[#E8506B]">
          {block.message}
          <button type="button" onClick={reload} className="rounded-full px-2 py-0.5 font-semibold ring-1 ring-line hover:text-blue">重试</button>
        </p>
      )}
      {block.state === 'ready' && items.length === 0 && (
        <p className="mt-3 text-xs text-muted">当前没有待处理的退款申请。</p>
      )}
      {block.state === 'ready' && items.length > 0 && (
        <>
          {!canApprove && (
            <p className="mt-2 rounded-lg bg-blue-mist px-3 py-2 text-[11px] leading-relaxed text-muted">
              你可以查看退款申请与进度；<span className="font-semibold text-ink">审批由门店店长、前台或平台管理员处理</span>。
            </p>
          )}
          <ul className="mt-3 flex flex-col gap-2.5">
            {items.map((r) => (
              <li key={r.id} className="rounded-xl bg-blue-mist/60 px-3.5 py-3 ring-1 ring-line">
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
                  <span className="font-mono text-xs font-semibold text-ink">{r.refundNo}</span>
                  <span className="text-sm font-black text-ink">{fenToYuanText(r.amount)}</span>
                  <Badge label={REFUND_STATUS_LABEL[r.status] ?? r.status} tone="pink" />
                  <span className="text-[11px] text-muted">预约 #{r.appointmentId ?? '—'} · 申请于 {orderTimeText(r.createdAt)}</span>
                  {canApprove && (
                    <span className="ml-auto flex items-center gap-2">
                      <button
                        type="button"
                        disabled={busyId === r.id}
                        onClick={() => void act(r, 'reject')}
                        className="h-8 rounded-lg bg-white px-3 text-xs font-semibold text-ink ring-1 ring-line transition hover:text-[#E8506B] hover:ring-[#E8506B] disabled:opacity-50"
                      >
                        {busyId === r.id ? '处理中…' : '拒绝'}
                      </button>
                      <button
                        type="button"
                        disabled={busyId === r.id}
                        onClick={() => void act(r, 'approve')}
                        className="h-8 rounded-lg px-3 text-xs font-bold text-white transition disabled:opacity-50"
                        style={{ background: '#2E6BFF' }}
                      >
                        通过并退款
                      </button>
                    </span>
                  )}
                </div>
                <p className="mt-1.5 text-xs leading-relaxed text-muted">
                  客户理由：<span className="text-ink">{r.reason || '（未填写）'}</span>
                </p>
              </li>
            ))}
          </ul>
          {totalPages > 1 && (
            <div className="mt-3 flex items-center justify-end gap-2">
              <button
                type="button"
                disabled={page <= 1}
                onClick={() => onPageChange(page - 1)}
                className="h-8 rounded-full bg-white px-3 text-xs font-semibold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue disabled:opacity-40"
              >
                上一页
              </button>
              <span className="text-[11px] text-muted">
                第 {page} / {totalPages} 页 · 共 {total} 笔
              </span>
              <button
                type="button"
                disabled={page >= totalPages}
                onClick={() => onPageChange(page + 1)}
                className="h-8 rounded-full bg-white px-3 text-xs font-semibold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue disabled:opacity-40"
              >
                下一页
              </button>
            </div>
          )}
          <p className="mt-2.5 text-[11px] leading-relaxed text-muted">
            口径：通过=直接记为退款成功（<span className="font-semibold text-ink">当前无支付渠道对接，属模拟执行</span>，
            不写渠道流水）；拒绝=订单回「已支付」，预约恢复「已确认」可继续服务，须填写拒绝理由（顾客可见）。
            审批单笔上限由平台参数 refund.auto_approve_limit_fen 控制，超额需平台管理员。
          </p>
        </>
      )}

      <Modal
        open={rejectTarget !== null}
        title="拒绝退款申请"
        description={
          rejectTarget
            ? `退款单 ${rejectTarget.refundNo} · ${fenToYuanText(rejectTarget.amount)}。拒绝后订单回到「已支付」，预约恢复「已确认」可继续服务。`
            : undefined
        }
        onClose={() => setRejectTarget(null)}
        footer={
          <>
            <button
              type="button"
              onClick={() => setRejectTarget(null)}
              className="h-9 rounded-lg px-4 text-xs font-semibold text-muted ring-1 ring-line transition hover:text-ink"
            >
              取消
            </button>
            <button
              type="button"
              disabled={busyId === rejectTarget?.id}
              onClick={() => void confirmReject()}
              className="h-9 rounded-lg bg-[#E8506B] px-4 text-xs font-bold text-white transition disabled:opacity-50"
            >
              {busyId === rejectTarget?.id ? '提交中…' : '确认拒绝'}
            </button>
          </>
        }
      >
        <label className="block text-xs font-semibold text-ink" htmlFor="reject-reason">
          拒绝理由（必填，顾客可见）
        </label>
        <textarea
          id="reject-reason"
          value={rejectReason}
          onChange={(e) => {
            setRejectReason(e.target.value)
            if (rejectError) setRejectError('')
          }}
          rows={3}
          maxLength={200}
          placeholder="例如：当日已出勤配料，无法取消；建议改期使用"
          className="mt-1.5 w-full rounded-xl bg-blue-mist/60 px-3 py-2 text-sm text-ink ring-1 ring-line outline-none transition placeholder:text-muted/60 focus:ring-blue"
        />
        <p className="mt-1 text-right text-[11px] text-muted">{rejectReason.trim().length}/200</p>
        {rejectError && <p className="mt-1 text-[11px] font-semibold text-[#E8506B]">{rejectError}</p>}
      </Modal>
    </div>
  )
}

/* ---- 订单行 + 行内展开（展开时拉详情，顺带在真实环境验证跨店 10004 口径） ---- */

function OrderBlock({
  order: o,
  open,
  onToggle,
}: {
  order: OrderItem
  open: boolean
  onToggle: () => void
}) {
  return (
    <div className="overflow-hidden rounded-2xl bg-blue-mist/60 ring-1 ring-line">
      <button type="button" aria-expanded={open} onClick={onToggle} className="flex w-full items-center gap-4 p-4 text-left transition hover:bg-white/60">
        <span className="grid h-12 w-12 shrink-0 place-items-center rounded-xl bg-white text-blue ring-1 ring-line">
          <IconWallet className="h-5 w-5" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="flex flex-wrap items-center gap-2 text-base font-black text-ink">
            <span className="truncate">{o.serviceName ?? '服务已下架'}</span>
            <Badge label={ORDER_STATUS_LABEL[o.status as OrderStatus] ?? o.status} tone={statusTone(o.status)} />
            {o.paymentMethod && <span className="shrink-0 rounded-full bg-white px-2 py-0.5 text-[11px] font-semibold text-muted ring-1 ring-line">{PAYMENT_METHOD_LABEL[o.paymentMethod] ?? o.paymentMethod}</span>}
          </p>
          <p className="mt-1 truncate text-sm text-muted">
            <span className="font-mono">{o.orderNo}</span>
            {' · '}
            {o.customer ? `${o.customer.nickname ?? '未命名客户'} ${o.customer.phoneMasked ?? '—'}` : '客户账号已注销'}
            {o.customer?.status === 'DISABLED' && '（已停用）'}
          </p>
          <p className="mt-0.5 text-[11px] text-muted">下单 {orderTimeText(o.createdAt)}{o.durationMin != null && ` · 时长 ${o.durationMin} 分钟`}</p>
        </div>
        <div className="hidden shrink-0 text-right sm:block">
          <p className="text-base font-black text-ink">{fenToYuanText(o.amount)}</p>
          <p className="text-[11px] text-muted">实收 {fenToYuanText(o.paidAmount)}</p>
        </div>
        <IconChevronDown className={cx('h-4 w-4 shrink-0 text-muted transition-transform', open && 'rotate-180')} />
      </button>
      {open && <OrderDetail orderId={o.id} />}
    </div>
  )
}

function OrderDetail({ orderId }: { orderId: string }) {
  const { block, reload } = useAsync(() => ordersApi.detail(orderId), orderId)
  const { push } = useToast()
  const myUserId = tokenStore.getUserId()
  /** 'register'=登记线下收款 / 'confirm'=复核他人登记；null=关 */
  const [offlineModal, setOfflineModal] = useState<'register' | 'confirm' | null>(null)
  const [offlineNote, setOfflineNote] = useState('')
  const [offlineError, setOfflineError] = useState('')
  const [offlineBusy, setOfflineBusy] = useState(false)
  if (block.state === 'loading') {
    return (
      <p className="border-t border-line bg-white px-4 py-6 text-center text-xs text-muted">
        <Spinner /> 加载订单详情…
      </p>
    )
  }
  if (block.state === 'error') {
    return (
      <div className="flex flex-col items-center gap-2 border-t border-line bg-white px-4 py-6">
        <p className="text-xs text-[#E8506B]">详情加载失败：{block.message}</p>
        <button type="button" onClick={reload} className="h-7 rounded-lg border border-line px-3 text-[11px] font-semibold text-ink hover:border-blue hover:text-blue">
          重试
        </button>
      </div>
    )
  }
  const o = block.data
  const offlinePending = o.offlinePayment?.status === 'PROCESSING' ? o.offlinePayment : null
  // 双人原则的前端提示口径：登记人本人不给复核按钮（后端 10003 是权威闸，这里只免一次必然失败的操作）
  const selfRegistered = offlinePending != null
    && offlinePending.registeredBy != null
    && offlinePending.registeredBy === myUserId

  const submitRegister = async () => {
    setOfflineBusy(true)
    try {
      await ordersApi.registerOffline(o.id, offlineNote.trim() || undefined)
      push('已登记线下收款：仅记录收款事实，等待其他员工复核后才到账')
      setOfflineModal(null)
      reload()
    } catch (err) {
      setOfflineError(err instanceof BizError ? err.message : '操作失败，请重试')
    } finally {
      setOfflineBusy(false)
    }
  }

  const submitConfirm = async () => {
    setOfflineBusy(true)
    try {
      const done = await ordersApi.confirmOffline(o.id)
      push(`线下收款已复核：${fenToYuanText(done.amount)} 记为到账，订单已推进「已支付」`)
      setOfflineModal(null)
      reload()
    } catch (err) {
      setOfflineError(err instanceof BizError ? err.message : '操作失败，请重试')
    } finally {
      setOfflineBusy(false)
    }
  }

  return (
    <div className="flex flex-col gap-4 border-t border-line bg-white p-4">
      <div className="grid gap-x-6 gap-y-2.5 sm:grid-cols-2 xl:grid-cols-3">
        <Field label="订单号" value={o.orderNo} mono />
        <Field label="关联预约号" value={o.appointmentId ?? '—'} mono />
        <Field label="状态" value={ORDER_STATUS_LABEL[o.status as OrderStatus] ?? o.status} />
        <Field label="应付金额" value={fenToYuanText(o.amount)} />
        <Field label="实收金额" value={fenToYuanText(o.paidAmount)} />
        <Field label="支付方式" value={o.paymentMethod ? PAYMENT_METHOD_LABEL[o.paymentMethod] ?? o.paymentMethod : '未支付'} />
        <Field label="下单时间" value={orderTimeText(o.createdAt)} />
        <Field label="支付时间" value={orderTimeText(o.paidAt)} />
        <Field label="支付截止" value={orderTimeText(o.payExpireAt)} />
      </div>

      <section>
        <p className="mb-2 flex items-center gap-1.5 text-xs font-bold text-muted">
          <IconUsers className="h-3.5 w-3.5 text-blue" /> 下单客户
        </p>
        {o.customer ? (
          <div className="flex flex-wrap items-center gap-3 rounded-xl bg-blue-mist/60 px-3 py-2.5 ring-1 ring-line">
            <span className="text-sm font-bold text-ink">{o.customer.nickname ?? '未命名客户'}</span>
            <span className="font-mono text-xs text-muted">{o.customer.phoneMasked ?? '—'}</span>
            <Badge label={o.customer.status === 'NORMAL' ? '账号正常' : '账号已停用'} tone={o.customer.status === 'NORMAL' ? 'green' : 'pink'} />
            <span className="ml-auto text-[11px] text-muted">手机号由服务端脱敏，门店侧无明文查询入口</span>
          </div>
        ) : (
          <p className="text-xs text-muted">该客户账号已注销或不可见</p>
        )}
      </section>

      <div className="flex flex-wrap gap-2.5 border-t border-line pt-3">
        {o.status === 'WAIT_PAY' && !offlinePending && (
          <ActionButton
            Icon={IconWallet}
            label="登记线下收款"
            onClick={() => {
              setOfflineModal('register')
              setOfflineNote('')
              setOfflineError('')
            }}
          />
        )}
        {o.status === 'WAIT_PAY' && offlinePending && (selfRegistered ? (
          <p className="flex items-center gap-2 rounded-xl bg-blue-mist px-3.5 py-2.5 text-xs text-muted ring-1 ring-line">
            <IconClock className="h-3.5 w-3.5 text-blue" />
            你已登记线下收款（{fenToYuanText(offlinePending.amount)}），等待其他员工复核——登记人与复核人不得相同
          </p>
        ) : (
          <ActionButton
            Icon={IconWallet}
            label="复核线下收款"
            onClick={() => {
              setOfflineModal('confirm')
              setOfflineError('')
            }}
          />
        ))}
      </div>
      <p className="text-[11px] leading-relaxed text-muted">
        订单创建后金额与服务即按快照锁定，不支持改价/改单——如需变更请取消后重新下单；
        退款审批不在这里——本页顶部「待处理退款」面板统一处理（ADR-034）。
      </p>

      <Modal
        open={offlineModal !== null}
        title={offlineModal === 'confirm' ? '复核线下收款' : '登记线下收款'}
        description={
          offlineModal === 'confirm' && offlinePending
            ? `订单 ${o.orderNo} · ${fenToYuanText(offlinePending.amount)}。登记人：${offlinePending.registrantName ?? '员工 ' + (offlinePending.registeredBy ?? '—')}（登记于 ${orderTimeText(offlinePending.createdAt)}）。确认到账后订单推进「已支付」，预约确认可服务。`
            : `订单 ${o.orderNo} · ${fenToYuanText(o.amount)}。登记只记录收款事实、不推进订单；到账须由另一名员工复核确认（双人原则）。`
        }
        onClose={() => setOfflineModal(null)}
        footer={
          <>
            <button
              type="button"
              onClick={() => setOfflineModal(null)}
              className="h-9 rounded-lg px-4 text-xs font-semibold text-muted ring-1 ring-line transition hover:text-ink"
            >
              取消
            </button>
            <button
              type="button"
              disabled={offlineBusy}
              onClick={() => void (offlineModal === 'confirm' ? submitConfirm() : submitRegister())}
              className="h-9 rounded-lg bg-[#2FA96B] px-4 text-xs font-bold text-white transition disabled:opacity-50"
            >
              {offlineBusy ? '提交中…' : offlineModal === 'confirm' ? '确认到账' : '完成登记'}
            </button>
          </>
        }
      >
        {offlineModal === 'register' && (
          <>
            <label className="block text-xs font-semibold text-ink" htmlFor="offline-note">
              登记备注（选填，复核人可见）
            </label>
            <textarea
              id="offline-note"
              value={offlineNote}
              onChange={(e) => {
                setOfflineNote(e.target.value)
                if (offlineError) setOfflineError('')
              }}
              rows={3}
              maxLength={200}
              placeholder="例如：现金已收，找零 12 元"
              className="mt-1.5 w-full rounded-xl bg-blue-mist/60 px-3 py-2 text-sm text-ink ring-1 ring-line outline-none transition placeholder:text-muted/60 focus:ring-blue"
            />
            <p className="mt-1 text-right text-[11px] text-muted">{offlineNote.trim().length}/200</p>
          </>
        )}
        {offlineError && <p className="text-[11px] font-semibold text-[#E8506B]">{offlineError}</p>}
      </Modal>
    </div>
  )
}

function statusTone(status: string): Tone {
  if (status === 'COMPLETED') return 'green'
  if (status === 'PAID') return 'blue'
  if (status === 'WAIT_PAY') return 'orange'
  if (status === 'WAIT_SERVICE') return 'purple'
  if (status === 'SERVING') return 'green'
  if (status === 'CANCELLED') return 'gray'
  return 'pink'
}

/* ---- 右栏 ---- */

function RangeCard({ rows, total, loading, tab }: { rows: OrderItem[]; total: number; loading: boolean; tab: Tab }) {
  const sum = pagePaidSum(rows)
  const unpaid = rows.filter((r) => r.status === 'WAIT_PAY').length
  const distribution = ORDER_STATUSES.map((s) => ({ s, n: rows.filter((r) => r.status === s).length })).filter((d) => d.n > 0)
  return (
    <Card title="区间概览">
      {loading ? (
        <p className="py-6 text-center text-xs text-muted">
          <Spinner /> 加载中…
        </p>
      ) : (
        <>
          <div className="grid grid-cols-2 gap-2">
            <Stat label="筛选结果总数" value={String(total)} real />
            <Stat label="当前页实收" value={fenToYuanText(sum)} />
          </div>
          <p className="mt-2 text-[11px] leading-relaxed text-muted">
            「当前页实收」只统计本页 {rows.length} 条，不是区间合计——全区间金额汇总需后端聚合端点（P8 未规划，不前端硬算）。
          </p>
          {unpaid > 0 && (
            <p className="mt-3 flex items-center gap-1.5 rounded-lg bg-[#FFF8E7] px-3 py-2 text-[11px] text-[#8A6116]">
              <IconClock className="h-3.5 w-3.5 shrink-0" /> 当前页有 {unpaid} 单待支付，超时将自动取消
            </p>
          )}
          <p className="mt-4 mb-2 text-xs font-bold text-muted">当前页状态分布{tab !== 'ALL' && `（已按「${ORDER_STATUS_LABEL[tab]}」过滤）`}</p>
          {distribution.length === 0 ? (
            <p className="text-xs text-muted">当前页没有订单</p>
          ) : (
            <ul className="flex flex-col gap-2">
              {distribution.map(({ s, n }) => (
                <li key={s} className="flex items-center gap-2">
                  <span className="w-[64px] shrink-0 truncate text-[11px] text-ink">{ORDER_STATUS_LABEL[s]}</span>
                  <span className="h-2 flex-1 overflow-hidden rounded-full bg-blue-mist">
                    <span className="block h-full rounded-full bg-blue" style={{ width: `${(n / Math.max(1, rows.length)) * 100}%` }} />
                  </span>
                  <span className="w-6 shrink-0 text-right text-[11px] font-bold text-ink">{n}</span>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </Card>
  )
}

function BoundaryCard({ onNotReady }: { onNotReady: (label: string) => () => void }) {
  return (
    <Card title="本页能力边界">
      <ul className="flex flex-col gap-2.5 text-[13px]">
        <li className="flex items-start gap-2">
          <span className="mt-1 h-1.5 w-1.5 shrink-0 rotate-45 bg-[#2FA96B]" />
          <span className="text-ink">已支持：本店订单列表、状态与日期筛选、订单号前缀搜索（与页签/日期组合）、分页、订单详情与下单客户（脱敏）、线下收款登记/复核、待处理退款审批（ADR-034，店长/平台管理员可批）、导出对账单 CSV（同一筛选条件，手机号脱敏，上限 5000 行）</span>
        </li>
        {[
          ['真实渠道退款', '当前审批通过=模拟记为已退款，未接支付渠道 SPI、不写渠道流水（ADR-034 §6）'],
          ['退款消息投递', '已落 ntf_notification（客户"审核中/退款成功"、店长"待处理"），但无未读与推送端点，界面上看不到'],
        ].map(([label, why]) => (
          <li key={label} className="flex items-start gap-2">
            <span className="mt-1 h-1.5 w-1.5 shrink-0 rotate-45 bg-[#E8506B]" />
            <button type="button" onClick={onNotReady(label)} className="min-w-0 flex-1 text-left">
              <span className="font-semibold text-ink">{label}</span>
              <span className="block text-[11px] text-muted">{why}</span>
            </button>
          </li>
        ))}
      </ul>
    </Card>
  )
}

function NoteCard() {
  return (
    <section className="rounded-2xl bg-gradient-to-br from-[#FFF8EC] to-white p-5 shadow-soft ring-1 ring-line">
      <h2 className="mb-2 flex items-center gap-2 text-base font-bold text-ink">
        <span className="grid h-7 w-7 place-items-center rounded-full bg-[#FFF1E0] text-[#F59E0B]">
          <IconBulb className="h-4 w-4" />
        </span>
        口径说明
      </h2>
      <ul className="flex flex-col gap-2 text-xs leading-relaxed text-muted">
        <li>· 金额取订单创建时快照，后续改价不回溯（ADR-006-9）。</li>
        <li>· 日期区间为下单时间闭区间，含首尾两天。</li>
        <li>· 只统计当前工作门店，切店即换数据集；跨店订单按不存在处理（10004）。</li>
        <li>· 前台与店长同权读订单；美容师等任务角色无 <span className="font-mono">order:view:store</span>，看不到本页。</li>
      </ul>
    </section>
  )
}

/* ---- 通用小件 ---- */

function Stat({ label, value, real }: { label: string; value: string; real?: boolean }) {
  return (
    <div className="rounded-xl bg-blue-mist/60 px-3 py-2.5 ring-1 ring-line">
      <p className="text-lg font-black leading-tight text-ink">{value}</p>
      <p className="text-[11px] text-muted">{label}</p>
      {real && <p className="mt-0.5 text-[10px] text-muted">（服务端真实计数）</p>}
    </div>
  )
}

function Badge({ label, tone }: { label: string; tone: Tone }) {
  return (
    <span className="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold" style={{ background: TONE[tone].bg, color: TONE[tone].fg }}>
      {label}
    </span>
  )
}

function Field({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div>
      <p className="text-[11px] text-muted">{label}</p>
      <p className={cx('mt-0.5 text-sm font-semibold text-ink', mono && 'font-mono text-[13px]')}>{value}</p>
    </div>
  )
}

function ActionButton({ Icon, label, onClick }: { Icon: typeof IconWallet; label: string; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className="flex h-9 items-center gap-1.5 rounded-lg bg-white px-3.5 text-xs font-semibold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue">
      <Icon className="h-4 w-4" /> {label}
    </button>
  )
}

function Spinner() {
  return <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-blue border-t-transparent align-[-2px]" aria-hidden="true" />
}

function ListLoading() {
  return (
    <div className="flex flex-col gap-3" role="status" aria-label="订单列表加载中">
      {[0, 1, 2].map((i) => (
        <div key={i} className="flex h-[92px] items-center justify-center rounded-2xl bg-blue-mist/60 ring-1 ring-line">
          {i === 0 && (
            <span className="text-xs text-muted">
              <Spinner /> 加载订单列表…
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
      <p className="text-sm text-[#E8506B]">订单列表加载失败：{message}</p>
      <button type="button" onClick={onRetry} className="h-9 rounded-lg border border-line bg-white px-5 text-xs font-semibold text-ink transition hover:border-blue hover:text-blue">
        重试
      </button>
      <p className="max-w-[440px] text-center text-[11px] leading-relaxed text-muted">
        提示 <span className="font-mono">服务异常（HTTP 404）</span> 通常说明运行中的后端镜像还没带上订单端点（ADR-019 需重建并重启 app 容器）。
      </p>
    </div>
  )
}
