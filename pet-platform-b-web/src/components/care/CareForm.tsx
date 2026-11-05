import { useState, type ReactNode } from 'react'
import { cx } from '../../lib/cx'
import type { CareRecordBody, StayItem } from '../../api/boarding'

/**
 * 托管日常护理记录表单（喂食/饮水/遛弯/观察/异常上报），**托管页与「宠物照顾」工作台共用同一份实现**。
 *
 * 校验口径（与后端 BoardingStayService.addCare 对齐，前端只做防呆）：
 * - 次数/时长须为 0 或正整数；观察/异常说明 ≤500 字、食量描述 ≤50 字（对齐列宽与后端 @Size）；
 * - 异常上报必须填说明；只填日期不算内容——至少要有一项照护事实，避免写进一条空记录；
 * - 护理日期留空=后端业务今日（Asia/Shanghai），不得晚于今日且须在入住区间（后端权威校验，前端给 max 提示）。
 *
 * 表单不含照片：`CareRecordBody.photos` 字段存在，但上传通道（MinIO 选图→直传→提交 key）未接线，
 * 放一个必然传不出文件的照片控件就是假入口。
 */

/** 与 BoardingPage 的写侧表单同款样式常量：抽到这里后两页共用，不再各留一份。 */
export const INPUT = 'h-9 w-full rounded-lg bg-white px-3 text-sm text-ink outline-none ring-1 ring-line transition focus:ring-blue'
export const AREA = 'w-full rounded-lg bg-white p-3 text-sm text-ink outline-none ring-1 ring-line transition placeholder:text-muted focus:ring-blue'

export function FormShell({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="border-t border-line bg-blue-mist/40 px-5 py-4">
      <p className="text-sm font-bold text-ink">{title}</p>
      {children}
    </div>
  )
}

/** 本机历法今天（不用 toISOString，避免 UTC 偏移把「今天」变成昨天）。 */
function localToday(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** 计数类输入：空=不提交该字段；非负整数才合法（后端 @Min(0) 仍会兜底） */
function parseCount(raw: string): { value?: number; bad: boolean } {
  const t = raw.trim()
  if (!t) return { bad: false }
  const n = Number(t)
  if (!Number.isFinite(n) || !Number.isInteger(n) || n < 0) return { bad: true }
  return { value: n, bad: false }
}

const NAVY = 'bg-[#1E3A5F] text-white hover:bg-[#16304F]'

export function CareForm({
  stay,
  busy,
  onSubmit,
  onCancel,
}: {
  stay: StayItem
  busy: boolean
  onSubmit: (body: CareRecordBody) => void
  onCancel: () => void
}) {
  const [careDate, setCareDate] = useState('')
  const [feedCount, setFeedCount] = useState('')
  const [feedText, setFeedText] = useState('')
  const [waterCount, setWaterCount] = useState('')
  const [walkCount, setWalkCount] = useState('')
  const [walkMin, setWalkMin] = useState('')
  const [obs, setObs] = useState('')
  const [abnormal, setAbnormal] = useState(false)
  const [abDesc, setAbDesc] = useState('')

  const f = parseCount(feedCount)
  const w = parseCount(waterCount)
  const k = parseCount(walkCount)
  const km = parseCount(walkMin)
  const countBad = f.bad || w.bad || k.bad || km.bad
  const textLong = obs.length > 500 || feedText.length > 50 || abDesc.length > 500
  const abnMiss = abnormal && abDesc.trim() === ''
  const has = (c: { value?: number }) => c.value !== undefined
  // 只填日期不算内容：至少要有一项照护事实，避免写进一条空记录
  const nothing = !has(f) && !feedText.trim() && !has(w) && !has(k) && !has(km) && !obs.trim() && !abnormal
  const blocked = busy || countBad || textLong || abnMiss || nothing

  const submit = () => {
    const body: CareRecordBody = {
      careDate: careDate || undefined,
      feeding: f.value != null || feedText.trim() ? { count: f.value, amountText: feedText.trim() || undefined } : undefined,
      watering: w.value != null ? { count: w.value } : undefined,
      walk: k.value != null || km.value != null ? { count: k.value, durationMin: km.value } : undefined,
      observation: obs.trim() || undefined,
      abnormalFlag: abnormal,
      abnormalDesc: abnormal ? abDesc.trim() : undefined,
    }
    onSubmit(body)
  }

  return (
    <FormShell title={`新增护理记录 · ${stay.pet?.name ?? '未知宠物'}`}>
      <p className="mt-1 text-[11px] leading-relaxed text-muted">
        主责护理员或持护理权限的员工可提交；护理日期不得晚于今日且须在入住区间（{stay.checkinDate} ~ {stay.checkoutDate}）内，留空=系统业务今日。
        首条记录会把住单推进为「每日护理中」。照片上传依赖对象存储接线（未接），本表单不含照片。
      </p>

      <div className="mt-3 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <label className="flex flex-col gap-1 text-[11px] text-muted">
          护理日期（留空=今日）
          <input type="date" value={careDate} max={localToday()} onChange={(e) => setCareDate(e.target.value)} className={INPUT} />
        </label>
        <label className="flex flex-col gap-1 text-[11px] text-muted">
          喂食次数
          <input inputMode="numeric" value={feedCount} onChange={(e) => setFeedCount(e.target.value)} placeholder="如 3" className={INPUT} />
        </label>
        <label className="flex flex-col gap-1 text-[11px] text-muted">
          食量描述（≤50 字）
          <input value={feedText} onChange={(e) => setFeedText(e.target.value)} placeholder="如 每餐约一半" className={INPUT} />
        </label>
        <label className="flex flex-col gap-1 text-[11px] text-muted">
          饮水次数
          <input inputMode="numeric" value={waterCount} onChange={(e) => setWaterCount(e.target.value)} placeholder="如 2" className={INPUT} />
        </label>
        <label className="flex flex-col gap-1 text-[11px] text-muted">
          遛弯次数
          <input inputMode="numeric" value={walkCount} onChange={(e) => setWalkCount(e.target.value)} placeholder="如 2" className={INPUT} />
        </label>
        <label className="flex flex-col gap-1 text-[11px] text-muted">
          遛弯总时长（分钟）
          <input inputMode="numeric" value={walkMin} onChange={(e) => setWalkMin(e.target.value)} placeholder="如 40" className={INPUT} />
        </label>
      </div>

      <textarea value={obs} onChange={(e) => setObs(e.target.value)} rows={2} placeholder="观察记录（可选，≤500 字）：精神、食欲、排泄、伤口…" className={cx(AREA, 'mt-3')} />

      <label className="mt-2 flex items-center gap-2 text-xs text-ink">
        <input type="checkbox" checked={abnormal} onChange={(e) => setAbnormal(e.target.checked)} className="h-4 w-4 accent-[#E8506B]" />
        标记为异常上报
        <span className="text-[11px] text-muted">（勾选后必须填写异常说明；异常不会自动改住单状态，会通知店长与护理员）</span>
      </label>
      {abnormal && (
        <textarea value={abDesc} onChange={(e) => setAbDesc(e.target.value)} rows={2} placeholder="异常说明（必填，≤500 字）" className={cx(AREA, 'mt-2 ring-[#E8506B]')} />
      )}

      <div className="mt-2 flex items-center justify-between">
        <span className="text-[11px] text-muted">
          {countBad ? '次数/时长须为 0 或正整数' : textLong ? '文案超出长度限制（观察/异常 500 字、食量 50 字）' : abnMiss ? '异常上报必须填写异常说明' : nothing ? '至少填写一项内容' : ''}
        </span>
        <div className="flex gap-2">
          <button type="button" onClick={onCancel} className="h-9 rounded-lg bg-white px-4 text-xs font-bold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue">
            取消
          </button>
          <button type="button" disabled={blocked} onClick={submit} className={cx('h-9 rounded-lg px-4 text-xs font-bold transition', NAVY, blocked && 'opacity-60')}>
            {busy ? '提交中…' : '提交记录'}
          </button>
        </div>
      </div>
    </FormShell>
  )
}
