import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import Layout from '../components/layout/Layout'
import Card from '../components/ui/Card'
import { IconClock, IconPaw } from '../components/ui/icons'
import { useToast } from '../components/ui/Toast'
import { storeContext } from '../auth/storeContext'
import { BizError } from '../api/types'
import { boardingApi, careSummary, IN_STORE_STATUSES, isInStore, type CareActivityEntry, type CareRecord, type StayItem } from '../api/boarding'
import { dateOfOffset } from '../api/bathing'
import { CareForm } from '../components/care/CareForm'
import { mediaSrc } from '../lib/mediaSrc'
import { boardingCapabilities, canCareForStay } from '../lib/boardingPermission'
import { cx } from '../lib/cx'
import { TONE } from '../lib/tone'
import type { Tone } from '../mock/dashboard'

/**
 * B 端「宠物照顾」= 护理员的托管日常护理工作台，数据全是真实接口、零后端新增：
 * 在住住单 `GET /store/boarding-stays`（缺省=在店集合）+ 全店护理动态 `GET /store/boarding/care-activity`
 * （ADR-032）+ 护理记录读写 `GET/POST /store/boarding-stays/{id}/care-records`（ADR-015）。
 * **不留演示回退**：拉不到就报错，不放假数据。
 *
 * 与「托管服务」页的分工：那边是托管业务全流程（入住/离店/选笼位/历史分页），这边只回答一个问题——
 * 「今天照顾了没有」：每只在住宠物今天有没有护理记录、该补没补、有没有异常，并提供最快的记护理入口。
 *
 * 范围与写权口径（都由服务端裁决，前端只如实标注）：
 * - 列表与动态按权限收窄：护理员只见自己主责的住单与记录，店长/前台看全店；
 * - 写侧「主责 ∨ 持 boarding:care:create 的本店员工」（ADR-015 §5 补记 2026-10-27，越权 70001）→
 *   无写权时按钮禁用并写明原因，不做假入口；记录 caretaker_id 落实际写入人，店长代录天然有审计。
 */
export default function CarePage() {
  const { push } = useToast()
  const ctx = useSyncExternalStore(storeContext.subscribe, storeContext.get)
  const storeId = ctx.storeId
  const myEmployeeId = ctx.identity?.employeeId ?? null
  const currentStoreRoles = ctx.candidates.find((s) => s.storeId === storeId)?.roles ?? []
  const caps = boardingCapabilities(ctx.platformAdmin === true, currentStoreRoles)

  // 一次只展开一张卡（记护理 或 护理历史），避免多行状态散落
  const [formStayId, setFormStayId] = useState<string | null>(null)
  const [histStayId, setHistStayId] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // 只取在住集合（CHECKED_IN/DAILY_CARE）：工作台不关心待入住/已离店/异常历史单（那是托管页的页签）
  const stays = useAsync(() => boardingApi.stays(IN_STORE_STATUSES), storeId)
  /** 独立成块降级：动态卡拉不到只影响右栏与"今日已记录"角标，不拖垮住单列表。 */
  const activity = useAsync(() => boardingApi.careActivity(), storeId)

  const stayRows = stays.block.state === 'ready' ? stays.block.data : []
  const today = dateOfOffset(0)
  // stayId → 今日该住单的护理记录（一个住单一天可以有多条：早喂食/晚喂食分开记）
  const todayByStay = new Map<string, CareActivityEntry[]>()
  if (activity.block.state === 'ready') {
    for (const e of activity.block.data) {
      if (e.careDate !== today) continue
      const arr = todayByStay.get(e.stayId)
      if (arr) arr.push(e)
      else todayByStay.set(e.stayId, [e])
    }
  }
  const careDone = stayRows.filter((s) => todayByStay.has(s.id)).length
  const abnormalCount = activity.block.state === 'ready' ? activity.block.data.filter((e) => e.abnormalFlag && e.careDate === today).length : null

  const doCare = async (stay: StayItem, body: Parameters<typeof boardingApi.addCareRecord>[1]) => {
    if (busy) return
    setBusy(true)
    const who = stay.pet?.name ?? '住单'
    try {
      await boardingApi.addCareRecord(stay.id, body)
      push(`${who} 的护理记录已提交`, 'success')
      setFormStayId(null)
      // 首条记录会把住单 CHECKED_IN 推进为 DAILY_CARE：两份真数据都要回接口拉，不就地改
      stays.reload()
      activity.reload()
    } catch (err) {
      push(err instanceof BizError ? err.message : '提交失败，请稍后重试', 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Layout active="care">
      <div className="grid gap-5 p-4 sm:p-6 2xl:grid-cols-[minmax(0,1fr)_320px]">
        <div className="flex min-w-0 flex-col gap-5">
          <div className="rounded-2xl bg-white p-5 shadow-soft ring-1 ring-line sm:p-6">
            <div>
              <h1 className="text-[26px] font-black text-ink">宠物照顾</h1>
              <p className="mt-1.5 text-sm text-muted">托管日常护理工作台：今天照顾了没有、该补哪只、有没有异常</p>
              <p className="mt-1 text-[11px] text-muted">
                数据源 <code className="font-mono">GET /store/boarding-stays</code> +{' '}
                <code className="font-mono">GET /store/boarding/care-activity</code>（真实）·
                写侧 <code className="font-mono">POST /store/boarding-stays/&#123;id&#125;/care-records</code> ·
                口径：{stayRows.length >= 0 && activity.block.state === 'ready' ? '列表与动态都由服务端按权限收窄（护理员=本人主责单）' : '加载中'}
              </p>
            </div>

            <div className="mt-5 flex flex-col gap-3">
              {stays.block.state === 'loading' && <ListLoading />}
              {stays.block.state === 'error' && <ListError message={stays.block.message} onRetry={stays.reload} />}
              {stays.block.state === 'denied' && (
                <p className="rounded-2xl bg-blue-mist/60 py-12 text-center text-sm text-muted">
                  当前账号没有 <code className="font-mono">boarding:care:view</code> 权限，看不到在住宠物列表——属权限口径，不是加载失败。
                </p>
              )}
              {stays.block.state === 'ready' && stayRows.length === 0 && (
                <p className="rounded-2xl bg-blue-mist/60 py-12 text-center text-sm text-muted">
                  当前没有在店的托管宠物（{today}）
                  {myEmployeeId == null ? '；当前账号也没有员工身份，即使有也只对店长可见' : ''}
                  。办理入住请去「托管服务」页。
                </p>
              )}
              {stayRows.map((s) => {
                const canWrite = canCareForStay(caps, myEmployeeId)
                const todayEntries = todayByStay.get(s.id)
                return (
                  <StayCard
                    key={s.id}
                    stay={s}
                    todayEntries={todayEntries}
                    today={today}
                    canWrite={canWrite}
                    formOpen={formStayId === s.id}
                    histOpen={histStayId === s.id}
                    busy={busy}
                    onOpenForm={() => {
                      setFormStayId((v) => (v === s.id ? null : s.id))
                      setHistStayId(null)
                    }}
                    onOpenHist={() => {
                      setHistStayId((v) => (v === s.id ? null : s.id))
                      setFormStayId(null)
                    }}
                    onCare={(body) => void doCare(s, body)}
                    onCloseForm={() => setFormStayId(null)}
                  />
                )
              })}
            </div>
          </div>
        </div>

        <div className="flex min-w-0 flex-col gap-5">
          <TodayStatsCard
            total={stays.block.state === 'ready' ? stayRows.length : null}
            done={stays.block.state === 'ready' ? careDone : null}
            abnormal={abnormalCount}
            loading={stays.block.state === 'loading'}
            scopeNarrowed={myEmployeeId != null}
          />
          <TodayActivityCard block={activity.block} onRetry={activity.reload} today={today} />
          <BoundaryCard />
        </div>
      </div>
    </Layout>
  )
}

/* ---- 区块数据（与洗澡/遛狗页同模式） ---- */

type Block<T> = { state: 'loading' } | { state: 'denied' } | { state: 'error'; message: string } | { state: 'ready'; data: T }

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
        if (err instanceof BizError && err.code === 10003) setBlock({ state: 'denied' })
        else setBlock({ state: 'error', message: err instanceof BizError ? err.message : '加载失败，请重试' })
      },
    )
  }, [reloadKey, tick])

  return { block, reload: () => setTick((t) => t + 1) }
}

/* ---- 住单卡 ---- */

function StayCard({
  stay: s,
  todayEntries,
  today,
  canWrite,
  formOpen,
  histOpen,
  busy,
  onOpenForm,
  onOpenHist,
  onCare,
  onCloseForm,
}: {
  stay: StayItem
  todayEntries: CareActivityEntry[] | undefined
  today: string
  canWrite: boolean
  formOpen: boolean
  histOpen: boolean
  busy: boolean
  onOpenForm: () => void
  onOpenHist: () => void
  onCare: (body: Parameters<typeof boardingApi.addCareRecord>[1]) => void
  onCloseForm: () => void
}) {
  const pet = s.pet
  const url = mediaSrc(pet?.avatarUrl)
  const [imgFailed, setImgFailed] = useState(false)
  const avatar = url && !imgFailed ? url : null
  const latest = todayEntries?.[0]
  const hasAbnormalToday = todayEntries?.some((e) => e.abnormalFlag) === true
  const statusTone = s.status === 'DAILY_CARE' ? 'purple' : 'green'

  return (
    <div className="overflow-hidden rounded-2xl bg-blue-mist/60 ring-1 ring-line">
      <div className="flex items-center gap-4 p-4">
        <span className="grid h-12 w-12 shrink-0 place-items-center overflow-hidden rounded-xl bg-white text-blue ring-1 ring-line">
          {avatar ? <img src={avatar} alt={pet?.name ?? '宠物头像'} className="h-full w-full object-cover" onError={() => setImgFailed(true)} /> : <IconPaw className="h-6 w-6" />}
        </span>
        <div className="min-w-0 flex-1">
          <p className="flex flex-wrap items-center gap-2 text-base font-black text-ink">
            <span className="truncate">{pet?.name ?? '未命名宠物'}</span>
            <span className="rounded-full bg-white px-2 py-0.5 text-[11px] font-semibold text-muted ring-1 ring-line">
              {speciesText(pet?.species)} · {pet?.breed ?? '品种未登记'}
            </span>
            <Badge label={isInStore(s.status) ? (s.status === 'DAILY_CARE' ? '每日护理中' : '入住中') : s.status} tone={statusTone as Tone} />
            {hasAbnormalToday && <Badge label="今日有异常上报" tone="pink" />}
            {!todayEntries && <Badge label="今日还没记录 · 待补" tone="orange" />}
          </p>
          <p className="mt-1 truncate text-sm text-muted">
            入住第 {stayDayNo(s.checkinDate, today)} 天（{s.checkinDate} 起，计划 {s.checkoutDate} 离店） ·{' '}
            {s.customer?.nickname ? `主人 ${s.customer.nickname}${s.customer.phoneMasked ? ` · ${s.customer.phoneMasked}` : ''}` : '主人信息未授权（需 customer:view:store）'}
          </p>
          <p className="mt-0.5 flex flex-wrap items-center gap-x-3 text-[11px] text-muted">
            <span className={s.caretaker ? '' : 'font-semibold text-[#E8506B]'}>
              护理员：{s.caretaker?.name ?? '未指派（去「托管服务」页办理入住时指定）'}
              {canWrite && '（我）'}
            </span>
            <span>
              今日护理：
              {todayEntries
                ? `已记 ${todayEntries.length} 条 · ${careSummary(latest!) ?? '已记录，无明细字段'}`
                : '暂无记录'}
            </span>
          </p>
        </div>
        <div className="flex shrink-0 flex-col gap-2">
          <button
            type="button"
            onClick={canWrite ? onOpenForm : undefined}
            disabled={!canWrite}
            title={canWrite ? '记一条护理' : '需护理记录权限且绑定员工身份（主责∨持码，ADR-015 §5）'}
            className={cx(
              'h-9 rounded-lg px-3.5 text-xs font-bold transition',
              canWrite ? NAVY : 'cursor-not-allowed bg-blue-mist text-muted',
              formOpen && canWrite && 'opacity-60',
            )}
          >
            记一条护理
          </button>
          <button
            type="button"
            onClick={onOpenHist}
            className="h-9 rounded-lg bg-white px-3.5 text-xs font-semibold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue"
          >
            护理历史
          </button>
        </div>
      </div>

      {formOpen && canWrite && <CareForm stay={s} busy={busy} onSubmit={onCare} onCancel={onCloseForm} />}
      {formOpen && !canWrite && (
        <p className="border-t border-line bg-white px-5 py-3 text-[11px] text-muted">
          当前账号无护理记录写权（需持 boarding:care:create 且绑定员工身份；主责∨持码均可写，ADR-015 §5）——越权提交后端会回 70001/10003，这里不提供假入口。
        </p>
      )}
      {histOpen && <StayCareHistory stayId={s.id} />}
    </div>
  )
}

/** 护理历史：该住单全部记录倒序（含照片 presigned 缩略图）。独立请求，收起即弃。 */
function StayCareHistory({ stayId }: { stayId: string }) {
  const { block, reload } = useAsync(() => boardingApi.careRecords(stayId), stayId)
  return (
    <div className="border-t border-line bg-white p-4">
      {block.state === 'loading' && <p className="py-3 text-center text-xs text-muted"><Spinner /> 加载护理历史…</p>}
      {block.state === 'denied' && <p className="py-3 text-center text-xs text-muted">当前账号没有查看护理记录的权限。</p>}
      {block.state === 'error' && (
        <div className="flex flex-col items-center gap-2 py-3">
          <p className="text-xs text-[#E8506B]">{block.message}</p>
          <button type="button" onClick={reload} className="h-7 rounded-lg border border-line px-3 text-[11px] font-semibold text-ink hover:border-blue hover:text-blue">
            重试
          </button>
        </div>
      )}
      {block.state === 'ready' && block.data.length === 0 && (
        <p className="py-3 text-center text-xs text-muted">这条住单还没有任何护理记录。</p>
      )}
      {block.state === 'ready' && block.data.length > 0 && (
        <ul className="flex flex-col divide-y divide-line">
          {block.data.map((r) => (
            <CareHistoryRow key={r.id} record={r} />
          ))}
        </ul>
      )}
    </div>
  )
}

function CareHistoryRow({ record: r }: { record: CareRecord }) {
  const urls = (r.photoUrls ?? []).map((u) => mediaSrc(u)).filter((u): u is string => u != null)
  return (
    <li className="py-2.5">
      <p className="flex flex-wrap items-center gap-2 text-[13px] font-bold text-ink">
        <IconClock className="h-3.5 w-3.5 text-muted" />
        {r.careDate}
        {r.abnormalFlag && <Badge label="异常" tone="pink" />}
      </p>
      <p className="mt-0.5 text-xs leading-snug text-muted">{careSummary(r) ?? '已记录，无明细字段'}</p>
      {r.abnormalFlag && r.abnormalDesc && <p className="mt-0.5 text-xs leading-snug text-[#E8506B]">异常说明：{r.abnormalDesc}</p>}
      {urls.length > 0 && (
        <div className="mt-1.5 flex flex-wrap gap-2">
          {urls.map((u, i) => (
            <a key={i} href={u} target="_blank" rel="noreferrer" className="block h-14 w-14 overflow-hidden rounded-lg ring-1 ring-line">
              <img src={u} alt={`护理照片 ${i + 1}`} className="h-full w-full object-cover" />
            </a>
          ))}
        </div>
      )}
    </li>
  )
}

/* ---- 右栏 ---- */

function TodayStatsCard({
  total,
  done,
  abnormal,
  loading,
  scopeNarrowed,
}: {
  total: number | null
  done: number | null
  abnormal: number | null
  loading: boolean
  scopeNarrowed: boolean
}) {
  const pending = total != null && done != null ? total - done : null
  return (
    <Card title="今日照顾统计">
      {loading && total == null ? (
        <p className="py-6 text-center text-xs text-muted">
          <Spinner /> 加载中…
        </p>
      ) : (
        <>
          <ul className="flex flex-col gap-2">
            <StatRow label="在店宠物" value={total} />
            <StatRow label="今日已记录" value={done} tone="green" />
            <StatRow label="今日还没记录" value={pending} tone={pending != null && pending > 0 ? 'orange' : undefined} />
            <StatRow label="今日异常上报" value={abnormal} tone={abnormal != null && abnormal > 0 ? 'pink' : undefined} />
          </ul>
          <p className="mt-3 text-[11px] leading-relaxed text-muted">
            计数基于当前账号可见范围（护理员只见本人主责的单）；"已记录"= 今日有至少一条护理记录（一天可多条）。
          </p>
          {scopeNarrowed && (
            <p className="mt-2 text-[11px] leading-relaxed text-muted">
              判断口径：住单列表 × 今日护理动态本地 join（两份都是服务端收窄后的真数据，不额外发请求）。
            </p>
          )}
        </>
      )}
    </Card>
  )
}

function StatRow({ label, value, tone }: { label: string; value: number | null; tone?: Tone }) {
  return (
    <li className="flex items-center justify-between gap-2 text-[13px]">
      <span className="text-muted">{label}</span>
      <span
        className={cx('rounded-full px-2 py-0.5 text-xs font-black', value != null && value > 0 ? '' : 'text-ink')}
        style={tone && value != null && value > 0 ? { background: TONE[tone].bg, color: TONE[tone].fg } : undefined}
      >
        {value ?? '—'}
      </span>
    </li>
  )
}

function TodayActivityCard({ block, onRetry, today }: { block: Block<CareActivityEntry[]>; onRetry: () => void; today: string }) {
  const rows = block.state === 'ready' ? block.data.filter((e) => e.careDate === today) : []
  return (
    <Card title="今日护理动态（全店视野）">
      {block.state === 'loading' && <p className="py-4 text-center text-xs text-muted"><Spinner /> 加载中…</p>}
      {block.state === 'denied' && <p className="py-4 text-xs leading-relaxed text-muted">本卡需要 <code className="font-mono">boarding:care:view</code>；护理员只见自己主责单的记录。</p>}
      {block.state === 'error' && (
        <div className="flex flex-col items-center gap-2 py-4">
          <p className="text-xs text-[#E8506B]">{block.message}</p>
          <button type="button" onClick={onRetry} className="h-7 rounded-lg border border-line px-3 text-[11px] font-semibold text-ink hover:border-blue hover:text-blue">
            重试
          </button>
        </div>
      )}
      {block.state === 'ready' && rows.length === 0 && (
        <p className="py-4 text-center text-xs leading-relaxed text-muted">
          今日暂无护理记录（主责或持护理权限员工可填写，ADR-015 §5）。
        </p>
      )}
      {block.state === 'ready' && rows.length > 0 && (
        <>
          <ul className="divide-y divide-line">
            {rows.slice(0, 12).map((e) => {
              const summary = careSummary(e)
              return (
                <li key={e.recordId} className="py-2.5">
                  <p className="text-sm font-bold text-ink">
                    {e.petName ?? '未命名宠物'}
                    {e.abnormalFlag && (
                      <span className="ml-1.5 rounded-full px-2 py-0.5 text-[11px] font-semibold" style={{ background: TONE.pink.bg, color: TONE.pink.fg }}>
                        异常
                      </span>
                    )}
                  </p>
                  <p className="mt-0.5 text-xs leading-snug text-muted">
                    {summary ?? '已记录，无明细字段'}
                    {e.caretakerName && <span className="ml-1">· {e.caretakerName}</span>}
                  </p>
                </li>
              )
            })}
          </ul>
          <p className="mt-3 text-[11px] text-muted">
            今日共 {rows.length} 条记录{rows.length > 12 ? '（一屏只列前 12 条）' : ''}；动态卡刻意不含照片（逐条签名会把一次查询放大成 M 次，看图走住单的「护理历史」）。
          </p>
        </>
      )}
    </Card>
  )
}

function BoundaryCard() {
  return (
    <Card title="本页能力边界">
      <ul className="flex flex-col gap-2.5 text-[13px]">
        <li className="flex items-start gap-2">
          <span className="mt-1 h-1.5 w-1.5 shrink-0 rotate-45 bg-[#2FA96B]" />
          <span className="text-ink">
            已接入：在住列表、今日全店护理动态、单住单护理历史（含照片签名地址）、记护理（喂食/饮水/遛弯/观察/异常上报）。
            首条记录自动把住单推进「每日护理中」。
          </span>
        </li>
        <li className="flex items-start gap-2">
          <span className="mt-1 h-1.5 w-1.5 shrink-0 rotate-45 bg-[#2FA96B]" />
          <span className="text-ink">
            异常上报会实时通知店长与护理员（<code className="font-mono">CARE_ABNORMAL</code> 站内信，B 端铃铛可见）。
          </span>
        </li>
        {[
          ['照片上传', '记录表单不含照片输入：MinIO 选图→直传→提交 key 的通道未接到本表单（字段已留）'],
          ['入住 / 离店 / 选笼位', '属托管业务流，在「托管服务」页办理；本页只管在店期间的日常护理'],
          ['医疗记录（疫苗/病历）', 'medical 域未开工，宠物健康明细去「宠物管理」页看只读摘要'],
          ['跨天趋势 / 报表', '护理统计报表属「统计报表」菜单，后端无聚合端点，不做假图表'],
        ].map(([label, why]) => (
          <li key={label} className="flex items-start gap-2">
            <span className="mt-1 h-1.5 w-1.5 shrink-0 rotate-45 bg-[#E8506B]" />
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

/* ---- 通用小件 ---- */

const NAVY = 'bg-[#1E3A5F] text-white hover:bg-[#16304F]'

function speciesText(species: string | null | undefined): string {
  if (species === 'DOG') return '犬'
  if (species === 'CAT') return '猫'
  return species ?? '未知'
}

/** 入住第 N 天：入住当天 = 第 1 天；日期非法时返回 1（不猜负数）。 */
function stayDayNo(checkin: string, today: string): number {
  const a = Date.parse(`${checkin}T00:00:00`)
  const b = Date.parse(`${today}T00:00:00`)
  if (Number.isNaN(a) || Number.isNaN(b)) return 1
  return Math.max(1, Math.round((b - a) / 86_400_000) + 1)
}

function Badge({ label, tone }: { label: string; tone: Tone }) {
  return (
    <span className="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold" style={{ background: TONE[tone].bg, color: TONE[tone].fg }}>
      {label}
    </span>
  )
}

function Spinner() {
  return <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-blue border-t-transparent align-[-2px]" aria-hidden="true" />
}

function ListLoading() {
  return (
    <div className="flex flex-col gap-3" role="status" aria-label="住单加载中">
      {[0, 1, 2].map((i) => (
        <div key={i} className="flex h-[104px] items-center justify-center rounded-2xl bg-blue-mist/60 ring-1 ring-line">
          {i === 0 && (
            <span className="text-xs text-muted">
              <Spinner /> 加载在住宠物…
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
      <p className="text-sm text-[#E8506B]">列表加载失败：{message}</p>
      <button type="button" onClick={onRetry} className="h-9 rounded-lg border border-line bg-white px-5 text-xs font-semibold text-ink transition hover:border-blue hover:text-blue">
        重试
      </button>
    </div>
  )
}
