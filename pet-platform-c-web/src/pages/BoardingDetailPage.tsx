import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  CustomerCareRecordView,
  CustomerStayView,
  DEMO_PET_LABEL,
  STAY_STATUS_TEXT,
  stayApi,
  stayDayNo,
  stayStatusTone,
} from '../api/boarding'
import { BizCode, BizError } from '../api/types'
import { PetVO, petApi } from '../api/pet'
import { useBizFail } from '../auth/useBizFail'
import PageHeader from '../components/layout/PageHeader'
import PetAvatar from '../components/pet/PetAvatar'
import { useToast } from '../components/ui/Toast'
import { IconCheck, IconClock, IconHeart, IconPaw } from '../components/ui/icons'
import { cx } from '../lib/cx'
import { addDaysISO, dateText, todayISO } from '../lib/dateText'
import { SPECIES_LABEL } from '../lib/petText'

const RANGES = [
  { key: 'ALL', label: '全部' },
  { key: 'D3', label: '最近 3 天' },
  { key: 'D7', label: '最近 7 天' },
] as const

type RangeKey = (typeof RANGES)[number]['key']

/** ADR-015 §1 状态机的展示顺序（ABNORMAL 不在链上，异常只标记护理记录、不改住单状态）。 */
const FLOW: { status: keyof typeof STAY_STATUS_TEXT; label: string; time: (s: CustomerStayView) => string | null }[] = [
  { status: 'EXPECTED', label: '已下单待入住', time: () => null },
  { status: 'CHECKED_IN', label: '已办理入住', time: (s) => s.actualCheckinAt },
  { status: 'DAILY_CARE', label: '日常照护中', time: () => null },
  { status: 'CHECKED_OUT', label: '已办理离店', time: (s) => s.actualCheckoutAt },
]

const FLOW_ORDER = ['EXPECTED', 'CHECKED_IN', 'DAILY_CARE', 'CHECKED_OUT']

/**
 * 托管日报（GET /customer/boarding-stays/{id}[/care-records]，ADR-015 §4/§5）。
 * 只渲染后端真给的字段：结构化喂食/饮水/遛放 + 观察 + 照片 key + 异常标志与描述；
 * 不显示笼位号、员工 id、预约号（契约刻意最小化）。
 */
export default function BoardingDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const { push } = useToast()
  const fail = useBizFail()

  const [stay, setStay] = useState<CustomerStayView | null>(null)
  const [demo, setDemo] = useState(false)
  const [pet, setPet] = useState<PetVO | null>(null)
  const [records, setRecords] = useState<CustomerCareRecordView[] | null>(null)
  const [range, setRange] = useState<RangeKey>('ALL')
  const [loadErr, setLoadErr] = useState('')

  const load = useCallback(
    (r: RangeKey) => {
      const ac = new AbortController()
      stayApi
        .detail(id, ac.signal)
        .then(async (res) => {
          if (ac.signal.aborted) return
          setStay(res.stay)
          setDemo(res.demo)
          const from = r === 'D3' ? addDaysISO(todayISO(), -2) : r === 'D7' ? addDaysISO(todayISO(), -6) : undefined
          const rec = await stayApi.careRecords(id, from, todayISO()).catch((e) => {
            if (!ac.signal.aborted) fail(e)
            return { records: [] as CustomerCareRecordView[], demo: res.demo }
          })
          if (!ac.signal.aborted) setRecords(rec.records)
          if (!res.demo) {
            petApi
              .detail(res.stay.petId)
              .then((p) => {
                if (!ac.signal.aborted) setPet(p)
              })
              .catch(() => {
                /* 宠物不可见不影响日报本体 */
              })
          }
        })
        .catch((e) => {
          if (ac.signal.aborted) return
          if (e instanceof BizError && e.code === BizCode.NOT_FOUND) {
            push('看不到这条托管记录（不存在或不属于你的宠物）', 'error')
            navigate('/boarding', { replace: true })
            return
          }
          if (e instanceof BizError && e.code === BizCode.FORBIDDEN) {
            setLoadErr('当前账号没有查看托管日报的权限（boarding:care:view）')
            return
          }
          fail(e)
        })
      return () => ac.abort()
    },
    [id, fail, navigate, push],
  )

  useEffect(() => load(range), [load, range])

  const sorted = useMemo(() => (records ?? []).slice().sort((a, b) => b.careDate.localeCompare(a.careDate)), [records])
  const abnormalCount = useMemo(() => sorted.filter((r) => r.abnormalFlag).length, [sorted])

  if (!stay) {
    return (
      <main className="min-h-[100dvh] bg-brand-gradient">
        <div className="mx-auto max-w-md px-4 pb-12">
          <PageHeader title="托管日报" fallback="/boarding" />
          {loadErr ? (
            <p className="mt-6 rounded-2xl bg-rose-50 px-4 py-3 text-xs leading-relaxed text-rose-600 ring-1 ring-rose-200/70">
              {loadErr}
            </p>
          ) : (
            <div className="animate-pulse space-y-4 pt-4" aria-busy="true">
              <div className="h-32 rounded-3xl bg-white/60" />
              <div className="h-48 rounded-3xl bg-white/60" />
            </div>
          )}
        </div>
      </main>
    )
  }

  const label = pet ? pet.name : (DEMO_PET_LABEL[stay.petId] ?? '毛孩子')
  const reached = FLOW_ORDER.indexOf(stay.status === 'ABNORMAL' ? 'CHECKED_IN' : stay.status)

  return (
    <main className="min-h-[100dvh] bg-brand-gradient">
      <div className="mx-auto max-w-md px-4 pb-12">
        <PageHeader title="托管日报" fallback="/boarding" />

        {demo && (
          <p className="mb-3 rounded-2xl border border-gold/40 bg-gold/10 px-3 py-2 text-[11px] leading-relaxed text-navy">
            演示数据（旧后端构建无住单列表端点时才会进入）；字段结构与 ADR-015 §4/§5 的真实契约一致。
          </p>
        )}

        <section className="animate-fade-up rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5">
          <div className="flex items-start gap-3">
            {pet ? (
              <PetAvatar pet={pet} className="h-14 w-14" />
            ) : (
              <span className="grid h-14 w-14 shrink-0 place-items-center rounded-2xl bg-warmgray text-[10px] text-muted ring-1 ring-navy/5">
                演示
              </span>
            )}
            <div className="min-w-0 flex-1">
              <p className="flex items-center gap-2">
                <span className="truncate text-base font-bold text-ink">{label}</span>
                <span className={cx('shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold', stayStatusTone(stay.status))}>
                  {STAY_STATUS_TEXT[stay.status]}
                </span>
              </p>
              <p className="mt-1 text-[11px] text-muted">
                {pet ? `${SPECIES_LABEL[pet.species]}${pet.breed ? ` · ${pet.breed}` : ''} · ` : ''}
                {dateText(stay.checkinDate)} 至 {dateText(stay.checkoutDate)} · {stayDayNo(stay)}
              </p>
            </div>
          </div>

          <dl className="mt-4 grid grid-cols-2 gap-x-3 gap-y-3 border-t border-navy/5 pt-4 text-xs">
            <Item k="计划入住" v={dateText(stay.checkinDate)} />
            <Item k="计划离店" v={dateText(stay.checkoutDate)} />
            <Item k="实际入住" v={fmtTime(stay.actualCheckinAt)} />
            <Item k="实际离店" v={fmtTime(stay.actualCheckoutAt)} />
            <Item k="护理员" v={stay.caretakerName ?? '门店尚未指派'} />
            <Item k="异常记录" v={records ? (abnormalCount ? `${abnormalCount} 条` : '无') : '统计中…'} />
          </dl>

          {stay.specialInstructions && (
            <p className="mt-3 rounded-2xl bg-ivory px-3.5 py-2.5 text-[11px] leading-relaxed text-navy/80 ring-1 ring-gold/25">
              <span className="font-semibold">家长叮嘱：</span>
              {stay.specialInstructions}
            </p>
          )}
        </section>

        <section className="animate-fade-up mt-4 rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5" style={{ animationDelay: '70ms' }}>
          <h2 className="flex items-center gap-1.5 text-sm font-bold text-ink">
            <IconClock className="h-4 w-4 text-gold-dark" /> 托管进度
          </h2>
          <ol className="mt-3 space-y-2.5">
            {FLOW.map((step, i) => {
              const done = stay.status === 'ABNORMAL' ? i <= 1 : i <= reached
              const t = step.time(stay)
              return (
                <li key={step.status} className="flex items-center gap-2.5 text-xs">
                  <span
                    className={cx(
                      'grid h-5 w-5 shrink-0 place-items-center rounded-full',
                      done ? 'bg-emerald-500 text-white' : 'bg-warmgray text-transparent ring-1 ring-navy/5',
                    )}
                  >
                    <IconCheck className="h-3 w-3" />
                  </span>
                  <span className={done ? 'font-semibold text-ink' : 'text-muted'}>{step.label}</span>
                  {t && <span className="ml-auto text-[11px] text-muted">{t.slice(5, 16).replace('T', ' ')}</span>}
                </li>
              )
            })}
          </ol>
          {stay.status === 'ABNORMAL' && (
            <p className="mt-2.5 text-[11px] text-rose-600">
              住单处于异常状态（ADR-015 §1 预留态），请联系门店处理。
            </p>
          )}
        </section>

        <section className="animate-fade-up mt-4 rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5" style={{ animationDelay: '140ms' }}>
          <div className="flex items-center justify-between">
            <h2 className="flex items-center gap-1.5 text-sm font-bold text-ink">
              <IconPaw className="h-4 w-4 text-gold-dark" /> 每日照护记录
            </h2>
            <span className="text-[11px] text-muted">{records ? `${sorted.length} 条` : '加载中'}</span>
          </div>

          <div className="mt-3 flex gap-2">
            {RANGES.map((r) => {
              const on = r.key === range
              return (
                <button
                  key={r.key}
                  type="button"
                  onClick={() => setRange(r.key)}
                  className={cx(
                    'rounded-full px-3 py-1 text-[11px] font-semibold transition',
                    on ? 'bg-navy text-white' : 'bg-warmgray text-navy/70',
                  )}
                >
                  {r.label}
                </button>
              )
            })}
          </div>

          {records === null ? (
            <div className="mt-3 space-y-3" aria-busy="true">
              <div className="h-24 animate-pulse rounded-2xl bg-warmgray/70" />
              <div className="h-24 animate-pulse rounded-2xl bg-warmgray/70" />
            </div>
          ) : sorted.length === 0 ? (
            <p className="mt-3 rounded-2xl bg-warmgray px-3.5 py-3 text-[11px] leading-relaxed text-muted">
              {stay.status === 'EXPECTED'
                ? '还没入住，护理员会从办理入住当天开始逐条记录。'
                : '所选时间范围内还没有照护记录，换一个范围或稍后再来看。'}
            </p>
          ) : (
            <ul className="mt-3 space-y-3">
              {sorted.map((r) => (
                <RecordCard key={r.id} record={r} />
              ))}
            </ul>
          )}

          <p className="mt-4 border-t border-navy/5 pt-3 text-[11px] leading-relaxed text-muted">
            照片是对象存储的 key/URL，读侧由服务端签发 presigned 地址；护理记录未上传照片时该处为空。
            异常记录只做标记与描述（ADR-015 §5），不会改变住单状态。
          </p>
        </section>
      </div>
    </main>
  )
}

function RecordCard({ record }: { record: CustomerCareRecordView }) {
  const allPhotos = (record.photos ?? []).filter(Boolean)
  /** 读失败的签名地址（600s 过期即命中）从横条里摘掉，而不是留一格裂图 */
  const [failedPhotos, setFailedPhotos] = useState<Record<string, true>>({})
  const photos = allPhotos.filter((p) => !failedPhotos[p])
  const metrics = [
    { k: '喂食', v: record.feeding?.count ?? null, extra: record.feeding?.amountText },
    { k: '饮水', v: record.watering?.count ?? null, extra: null },
    { k: '遛放', v: record.walk?.count ?? null, extra: record.walk?.durationMin ? `${record.walk.durationMin} 分钟/次` : null },
  ]
  return (
    <li
      className={cx(
        'rounded-2xl p-3.5 ring-1',
        record.abnormalFlag ? 'bg-rose-50/60 ring-rose-200/70' : 'bg-white/80 ring-navy/5',
      )}
    >
      <div className="flex items-center gap-2">
        <span className="text-sm font-bold text-ink">{dateText(record.careDate)}</span>
        {record.abnormalFlag && (
          <span className="rounded-full bg-rose-500 px-2 py-0.5 text-[10px] font-semibold text-white">需关注</span>
        )}
      </div>

      <div className="mt-2.5 grid grid-cols-3 gap-2">
        {metrics.map((m) => (
          <div key={m.k} className="rounded-xl bg-warmgray/70 px-2.5 py-2 text-center">
            <p className="text-[10px] text-muted">{m.k}</p>
            <p className="mt-0.5 text-sm font-bold text-navy">{m.v === null ? '—' : `${m.v} 次`}</p>
            {m.extra && <p className="mt-0.5 truncate text-[10px] text-muted">{m.extra}</p>}
          </div>
        ))}
      </div>

      {record.observation && (
        <p className="mt-2.5 flex items-start gap-1.5 text-[11px] leading-relaxed text-ink">
          <IconHeart className="mt-0.5 h-3.5 w-3.5 shrink-0 text-gold-dark" />
          {record.observation}
        </p>
      )}

      {record.abnormalFlag && record.abnormalDesc && (
        <p className="mt-2 rounded-xl bg-white px-3 py-2 text-[11px] leading-relaxed text-rose-600 ring-1 ring-rose-200/70">
          {record.abnormalDesc}
        </p>
      )}

      {photos.length > 0 && (
        <div className="mt-2.5 flex gap-2 overflow-x-auto pb-1">
          {photos.map((p) => (
            <img
              key={p}
              src={p}
              alt="照护照片"
              onError={() => setFailedPhotos((prev) => ({ ...prev, [p]: true }))}
              loading="lazy"
              className="h-20 w-20 shrink-0 rounded-xl object-cover ring-1 ring-navy/10"
            />
          ))}
        </div>
      )}
    </li>
  )
}

function Item({ k, v }: { k: string; v: string }) {
  return (
    <div>
      <dt className="text-[11px] text-muted">{k}</dt>
      <dd className="mt-0.5 text-xs font-semibold text-ink">{v}</dd>
    </div>
  )
}

function fmtTime(s: string | null): string {
  return s ? s.slice(5, 16).replace('T', ' ') : '尚未发生'
}
