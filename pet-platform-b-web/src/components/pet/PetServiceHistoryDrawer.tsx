import { useCallback, useEffect, useRef, useState } from 'react'
import Drawer from '../ui/Drawer'
import { IconClock } from '../ui/icons'
import { BizError } from '../../api/types'
import { fenToYuanText } from '../../api/dashboard'
import { petsApi, type PetItem, type PetServiceRecord } from '../../api/pets'
import { TONE } from '../../lib/tone'
import {
  createLatestOnly,
  formatServiceDateTime,
  hasMoreServiceRecords,
  mergeServiceRecords,
  serviceHistoryErrorText,
  serviceRecordTypeLabel,
  serviceStatusView,
  type LatestOnlyGate,
} from '../../lib/petManage'
import { cx } from '../../lib/cx'

const PAGE_SIZE = 20

/**
 * 宠物当前门店服务历史只读抽屉。
 * 预约是主脊柱，履约/托管详情由后端按当前页批量补充；前端只做展示、分页和去重。
 */
export default function PetServiceHistoryDrawer({
  pet,
  storeId,
  onClose,
}: {
  pet: PetItem
  storeId: string
  onClose: () => void
}) {
  const [rows, setRows] = useState<PetServiceRecord[]>([])
  const [total, setTotal] = useState('0')
  const [lastPageCount, setLastPageCount] = useState(0)
  const [nextPage, setNextPage] = useState(1)
  const [loadedOnce, setLoadedOnce] = useState(false)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState<{ message: string; code: number; page: number } | null>(null)
  const gateRef = useRef<LatestOnlyGate | null>(null)
  if (!gateRef.current) gateRef.current = createLatestOnly()
  const gate = gateRef.current
  const abortRef = useRef<AbortController | null>(null)

  const fetchPage = useCallback(
    async (page: number, append: boolean) => {
      const isLatest = gate.begin()
      abortRef.current?.abort()
      const ac = new AbortController()
      abortRef.current = ac
      if (append) setLoadingMore(true)
      else setLoading(true)
      setError(null)
      try {
        const data = await petsApi.serviceHistory(
          pet.id,
          { pageNum: page, pageSize: PAGE_SIZE },
          { signal: ac.signal },
        )
        if (ac.signal.aborted || !isLatest()) return
        setRows((prev) => (append ? mergeServiceRecords(prev, data.list) : data.list))
        setTotal(data.total)
        setLastPageCount(data.list.length)
        setNextPage(page + 1)
        setLoadedOnce(true)
      } catch (err) {
        if (ac.signal.aborted || !isLatest()) return
        const biz = err instanceof BizError ? err : null
        setError({
          message: serviceHistoryErrorText(biz?.code ?? -1, biz?.message ?? ''),
          code: biz?.code ?? -1,
          page,
        })
        setLoadedOnce(true)
      } finally {
        if (!ac.signal.aborted && isLatest()) {
          setLoading(false)
          setLoadingMore(false)
        }
      }
    },
    [gate, pet.id],
  )

  useEffect(() => {
    setRows([])
    setTotal('0')
    setLastPageCount(0)
    setNextPage(1)
    setLoadedOnce(false)
    setLoading(true)
    setLoadingMore(false)
    setError(null)
    void fetchPage(1, false)
    return () => {
      abortRef.current?.abort()
      gate.invalidate()
    }
  }, [fetchPage, gate, pet.id, storeId])

  const hasMore = hasMoreServiceRecords(rows.length, total) && lastPageCount > 0
  const retry = () => {
    if (!error) return
    void fetchPage(error.page, rows.length > 0)
  }
  const loadMore = () => {
    if (loading || loadingMore || !hasMore) return
    void fetchPage(nextPage, true)
  }

  return (
    <Drawer
      open
      title={`服务记录 · ${pet.name}`}
      description="当前门店内该宠物的预约与服务历史；不含医疗、疫苗、处方和检查报告。"
      onClose={onClose}
      width="sm:max-w-[560px]"
      footer={
        <button
          type="button"
          onClick={onClose}
          className="h-10 rounded-xl bg-white px-4 text-sm font-semibold text-ink ring-1 ring-line transition hover:border-blue hover:text-blue"
        >
          关闭
        </button>
      }
    >
      <div className="mb-3 flex items-center justify-between gap-3">
        <p className="text-[11px] text-muted">
          {loadedOnce && !error && !loading ? `共 ${total} 条记录` : '当前门店服务历史'}
        </p>
        <span className="text-[11px] text-muted">按服务日期倒序</span>
      </div>

      {loading && rows.length === 0 && (
        <div className="flex flex-col items-center gap-2 py-14 text-xs text-muted" role="status">
          <Spinner /> 正在读取服务记录…
        </div>
      )}

      {error && rows.length === 0 && (
        <div className="flex flex-col items-center gap-3 py-12 text-center">
          <p className="text-xs leading-relaxed text-[#E8506B]">{error.message}</p>
          {error.code !== 20101 && error.code !== 10003 && (
            <button
              type="button"
              onClick={retry}
              className="h-8 rounded-lg border border-line bg-white px-4 text-xs font-semibold text-ink transition hover:border-blue hover:text-blue"
            >
              重试
            </button>
          )}
        </div>
      )}

      {!loading && !error && rows.length === 0 && (
        <div className="flex flex-col items-center gap-3 py-12 text-center">
          <span className="grid h-11 w-11 place-items-center rounded-full bg-warmgray text-muted">
            <IconClock className="h-5 w-5" />
          </span>
          <p className="text-xs text-muted">当前门店暂无该宠物的服务记录</p>
        </div>
      )}

      {rows.length > 0 && (
        <ul className="flex flex-col gap-2.5">
          {rows.map((row) => (
            <ServiceRecordCard key={row.appointmentId} row={row} />
          ))}
        </ul>
      )}

      {error && rows.length > 0 && (
        <div className="mt-3 flex items-center justify-between gap-3 rounded-lg bg-rose-50 px-3 py-2 text-[11px] text-[#E8506B] ring-1 ring-rose-100">
          <span className="min-w-0">{error.message}</span>
          <button type="button" onClick={retry} className="shrink-0 font-semibold underline-offset-2 hover:underline">
            重试
          </button>
        </div>
      )}

      {hasMore && (
        <div className="mt-4 flex justify-center">
          <button
            type="button"
            disabled={loadingMore || loading}
            onClick={loadMore}
            className="h-9 rounded-xl border border-line bg-white px-5 text-xs font-semibold text-ink transition hover:border-blue hover:text-blue disabled:cursor-not-allowed disabled:opacity-50"
          >
            {loadingMore ? '加载中…' : `加载更多（已显示 ${rows.length}/${total} 条）`}
          </button>
        </div>
      )}

      {!hasMore && rows.length > 0 && (
        <p className="mt-4 text-center text-[11px] text-muted">
          {loadingMore ? <Spinner /> : '已显示全部记录'}
        </p>
      )}
    </Drawer>
  )
}

function ServiceRecordCard({ row }: { row: PetServiceRecord }) {
  const status = serviceStatusView(row.status)
  const typeLabel = serviceRecordTypeLabel(row.recordType)
  const employee = row.employeeName
    ?? (row.boardingCaretakerName ? `护理员 ${row.boardingCaretakerName}` : '未指派')
  const timeText = row.startTime && row.endTime ? `${row.startTime}-${row.endTime}` : null
  return (
    <li className="min-w-0 overflow-hidden rounded-xl bg-white p-3.5 ring-1 ring-line">
      <div className="flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <p className="truncate text-[13px] font-bold text-ink" title={row.serviceName}>
            {row.serviceName}
          </p>
          <p className="mt-1 truncate text-[11px] text-muted">
            {row.serviceDate}
            {timeText ? ` · ${timeText}` : ''} · {typeLabel}
          </p>
        </div>
        <span
          className="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold"
          style={{ background: TONE[status.tone].bg, color: TONE[status.tone].fg }}
        >
          {status.label}
        </span>
      </div>

      <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted">
        <span className="min-w-0 truncate">执行人：{employee}</span>
        {row.finishedAt && <span>完成：{formatServiceDateTime(row.finishedAt)}</span>}
        {row.priceSnapshot != null && <span>金额：{fenToYuanText(row.priceSnapshot)}</span>}
      </div>

      <RecordSummary row={row} />
    </li>
  )
}

function RecordSummary({ row }: { row: PetServiceRecord }) {
  if (row.bathRecord) {
    const items = row.bathRecord.serviceItems?.filter(Boolean) ?? []
    return (
      <p className="mt-2 rounded-lg bg-blue-mist/60 px-2.5 py-2 text-[11px] leading-relaxed text-muted">
        洗澡/美容履约
        {items.length > 0 ? ` · 项目：${items.join('、')}` : ' · 项目未记录'}
        {` · 照片 ${row.bathRecord.photoCount} 张`}
        {row.bathRecord.remark ? ` · ${row.bathRecord.remark}` : ''}
      </p>
    )
  }
  if (row.walkRecord) {
    return (
      <p className="mt-2 rounded-lg bg-blue-mist/60 px-2.5 py-2 text-[11px] leading-relaxed text-muted">
        遛狗履约 · 时长 {row.walkRecord.durationMin} 分钟
        {row.walkRecord.remark ? ` · ${row.walkRecord.remark}` : ''}
      </p>
    )
  }
  if (row.boardingStay) {
    return (
      <p className="mt-2 rounded-lg bg-blue-mist/60 px-2.5 py-2 text-[11px] leading-relaxed text-muted">
        托管 · 入住 {row.boardingStay.checkinDate} · 计划离店 {row.boardingStay.checkoutDate}
        {` · ${boardingStatusLabel(row.boardingStay.status)}`}
      </p>
    )
  }
  return (
    <p className="mt-2 rounded-lg bg-warmgray/60 px-2.5 py-2 text-[11px] leading-relaxed text-muted">
      暂无履约详情，当前状态以预约为准。
    </p>
  )
}

function boardingStatusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'EXPECTED':
      return '待入住'
    case 'CHECKED_IN':
      return '已入住'
    case 'DAILY_CARE':
      return '每日护理中'
    case 'CHECKED_OUT':
      return '已离店'
    case 'ABNORMAL':
      return '异常'
    default:
      return status?.trim() || '状态未知'
  }
}

function Spinner() {
  return (
    <span
      className={cx('inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-blue border-t-transparent')}
      aria-hidden="true"
    />
  )
}
