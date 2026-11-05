import { useEffect, useRef, useState } from 'react'
import Drawer from '../ui/Drawer'
import { IconCalendar, IconHeartPulse, IconPaw } from '../ui/icons'
import { BizCode, BizError } from '../../api/types'
import {
  boardingApi,
  shortDateTime,
  STAY_STATUS_LABEL,
  type CareRecord,
  type StayDetail,
} from '../../api/boarding'
import type { PetItem } from '../../api/pets'
import {
  createLatestOnly,
  dailyReportErrorText,
  feedingText,
  reportCaretakerName,
  walkText,
  wateringText,
  type LatestOnlyGate,
} from '../../lib/petManage'
import { TONE } from '../../lib/tone'
import { cx } from '../../lib/cx'

const ONLY_ASSIGNED_CARETAKER = 70001

type Block =
  | { state: 'loading' }
  | { state: 'error'; message: string; code: number }
  | { state: 'ready'; detail: StayDetail; records: CareRecord[] }

/**
 * 托管日报只读抽屉：复用门店端住单详情 + 护理记录端点。
 * 门店/宠物/住单范围由后端 requireVisibleStay 强制校验；本组件不拼接 MinIO 地址。
 */
export default function BoardingDailyReportDrawer({
  pet,
  stayId,
  storeName,
  storeId,
  onClose,
}: {
  pet: PetItem
  stayId: string
  storeName: string
  storeId: string
  onClose: () => void
}) {
  const [block, setBlock] = useState<Block>({ state: 'loading' })
  const [tick, setTick] = useState(0)
  const gateRef = useRef<LatestOnlyGate | null>(null)
  if (!gateRef.current) gateRef.current = createLatestOnly()
  const gate = gateRef.current

  useEffect(() => {
    const isLatest = gate.begin()
    setBlock({ state: 'loading' })
    Promise.all([
      boardingApi.detail(stayId, pet.id),
      boardingApi.careRecords(stayId, pet.id),
    ]).then(
      ([detail, records]) => {
        if (!isLatest()) return
        setBlock({ state: 'ready', detail, records })
      },
      (err: unknown) => {
        if (!isLatest()) return
        const biz = err instanceof BizError ? err : null
        setBlock({
          state: 'error',
          message: dailyReportErrorText(biz?.code ?? -1, biz?.message ?? ''),
          code: biz?.code ?? -1,
        })
      },
    )
    return () => gate.invalidate()
  }, [gate, pet.id, stayId, storeId, tick])

  const canRetry =
    block.state === 'error'
    && block.code !== BizCode.FORBIDDEN
    && block.code !== BizCode.NOT_FOUND
    && block.code !== ONLY_ASSIGNED_CARETAKER

  return (
    <Drawer
      open
      title={`托管日报 · ${pet.name}`}
      description="当前门店托管住单的护理记录，只读查看。"
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
      {block.state === 'loading' && (
        <div className="flex flex-col items-center gap-2 py-14 text-xs text-muted" role="status">
          <Spinner /> 正在读取托管日报…
        </div>
      )}

      {block.state === 'error' && (
        <div className="flex flex-col items-center gap-3 py-12 text-center">
          <p className="text-xs leading-relaxed text-[#E8506B]">{block.message}</p>
          {canRetry && (
            <button
              type="button"
              onClick={() => setTick((t) => t + 1)}
              className="h-8 rounded-lg border border-line bg-white px-4 text-xs font-semibold text-ink transition hover:border-blue hover:text-blue"
            >
              重试
            </button>
          )}
        </div>
      )}

      {block.state === 'ready' && (
        <div className="flex flex-col gap-4">
          <StayHeader
            detail={block.detail}
            records={block.records}
            storeName={storeName}
          />
          {block.records.length === 0 ? (
            <EmptyReports />
          ) : (
            <div className="flex flex-col gap-3">
              <div className="flex items-center justify-between">
                <h3 className="flex items-center gap-1.5 text-xs font-bold text-muted">
                  <IconHeartPulse className="h-3.5 w-3.5 text-blue" />
                  日报记录（{block.records.length} 条）
                </h3>
                <span className="text-[11px] text-muted">最新在前</span>
              </div>
              {block.records.map((record) => (
                <DailyReportCard key={record.id} record={record} />
              ))}
            </div>
          )}
        </div>
      )}
    </Drawer>
  )
}

function StayHeader({
  detail,
  records,
  storeName,
}: {
  detail: StayDetail
  records: CareRecord[]
  storeName: string
}) {
  const caretakerName = reportCaretakerName(records)
  return (
    <section className="rounded-xl bg-blue-mist/60 p-3.5 ring-1 ring-line">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="text-[11px] font-semibold text-muted">当前门店</p>
          <p className="truncate text-[13px] font-bold text-ink" title={storeName}>{storeName}</p>
        </div>
        <span
          className="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold"
          style={stayStatusTone(detail.status)}
        >
          {STAY_STATUS_LABEL[detail.status] ?? detail.status}
        </span>
      </div>

      <dl className="mt-3 grid grid-cols-1 gap-x-4 gap-y-2 text-[11px] sm:grid-cols-2">
        <div>
          <dt className="text-muted">入住日期</dt>
          <dd className="mt-0.5 font-semibold text-ink">{detail.checkinDate || '未登记'}</dd>
        </div>
        <div>
          <dt className="text-muted">计划离店</dt>
          <dd className="mt-0.5 font-semibold text-ink">{detail.checkoutDate || '未登记'}</dd>
        </div>
        <div>
          <dt className="text-muted">实际入住</dt>
          <dd className="mt-0.5 font-semibold text-ink">{shortDateTime(detail.actualCheckinAt)}</dd>
        </div>
        <div>
          <dt className="text-muted">实际离店</dt>
          <dd className="mt-0.5 font-semibold text-ink">{shortDateTime(detail.actualCheckoutAt)}</dd>
        </div>
        <div>
          <dt className="text-muted">护理员</dt>
          <dd className="mt-0.5 font-semibold text-ink">
            {caretakerName ?? (detail.caretakerId ? '已指派（姓名暂无）' : '未指派')}
          </dd>
        </div>
        {detail.specialInstructions && (
          <div className="sm:col-span-2">
            <dt className="text-muted">特殊说明</dt>
            <dd className="mt-0.5 whitespace-pre-line break-words text-xs leading-relaxed text-ink">
              {detail.specialInstructions}
            </dd>
          </div>
        )}
      </dl>
    </section>
  )
}

function DailyReportCard({ record }: { record: CareRecord }) {
  const photos = record.photoUrls ?? []
  const photoKeysCount = record.photos?.length ?? 0
  return (
    <article className="min-w-0 overflow-hidden rounded-xl bg-white p-3.5 ring-1 ring-line">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="flex items-center gap-1.5 text-[13px] font-bold text-ink">
          <IconCalendar className="h-3.5 w-3.5 text-blue" />
          {record.careDate}
        </p>
        <div className="flex items-center gap-1.5">
          {record.abnormalFlag && (
            <span
              className="rounded-full px-2 py-0.5 text-[11px] font-semibold"
              style={{ background: TONE.pink.bg, color: TONE.pink.fg }}
            >
              异常
            </span>
          )}
          <span className="text-[11px] text-muted">
            {record.caretakerName ?? '护理员姓名未记录'}
          </span>
        </div>
      </div>

      <dl className="mt-3 grid grid-cols-1 gap-2.5 text-[11px] sm:grid-cols-2">
        <ReportField label="饮食" value={feedingText(record.feeding)} />
        <ReportField label="饮水" value={wateringText(record.watering)} />
        <ReportField label="活动 / 遛弯" value={walkText(record.walk)} />
        <ReportField label="护理观察" value={record.observation?.trim() || '未填写'} wide />
      </dl>

      {record.abnormalFlag && record.abnormalDesc && (
        <p className="mt-2 rounded-lg bg-rose-50 px-2.5 py-2 text-[11px] leading-relaxed text-[#E8506B] ring-1 ring-rose-100">
          异常说明：{record.abnormalDesc}
        </p>
      )}

      {photos.length > 0 && (
        <ul className="mt-3 flex flex-wrap gap-2">
          {photos.map((url, index) => (
            <li key={`${record.id}-${index}`}>
              <a
                href={url}
                target="_blank"
                rel="noreferrer"
                title={`第 ${index + 1} 张，点击查看原图`}
              >
                <img
                  src={url}
                  alt={`托管日报照片 ${index + 1}`}
                  className="h-20 w-20 max-w-full rounded-lg object-cover ring-1 ring-line transition hover:ring-blue"
                  loading="lazy"
                />
              </a>
            </li>
          ))}
        </ul>
      )}
      {photos.length === 0 && photoKeysCount > 0 && (
        <p className="mt-2 text-[11px] text-muted">含 {photoKeysCount} 张照片（暂无读取地址）</p>
      )}
    </article>
  )
}

function ReportField({ label, value, wide }: { label: string; value: string; wide?: boolean }) {
  return (
    <div className={wide ? 'sm:col-span-2' : undefined}>
      <dt className="text-muted">{label}</dt>
      <dd className="mt-0.5 whitespace-pre-line break-words text-xs leading-relaxed text-ink">{value}</dd>
    </div>
  )
}

function EmptyReports() {
  return (
    <div className="flex flex-col items-center gap-3 py-12 text-center">
      <span className="grid h-11 w-11 place-items-center rounded-full bg-warmgray text-muted">
        <IconPaw className="h-5 w-5" />
      </span>
      <p className="text-xs text-muted">该托管住单暂无日报记录</p>
    </div>
  )
}

function stayStatusTone(status: StayDetail['status']): { background: string; color: string } {
  if (status === 'CHECKED_IN') return { background: TONE.green.bg, color: TONE.green.fg }
  if (status === 'DAILY_CARE') return { background: TONE.purple.bg, color: TONE.purple.fg }
  if (status === 'CHECKED_OUT') return { background: TONE.gray.bg, color: TONE.gray.fg }
  if (status === 'ABNORMAL') return { background: TONE.pink.bg, color: TONE.pink.fg }
  return { background: TONE.blue.bg, color: TONE.blue.fg }
}

function Spinner() {
  return (
    <span
      className={cx('inline-block h-4 w-4 animate-spin rounded-full border-2 border-blue border-t-transparent')}
      aria-hidden="true"
    />
  )
}
