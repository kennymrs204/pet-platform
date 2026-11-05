import { useEffect, useRef, useState, type ReactNode } from 'react'
import Drawer from '../ui/Drawer'
import {
  IconCheckCircle,
  IconClipboard,
  IconClock,
  IconEdit,
  IconPaw,
} from '../ui/icons'
import { BizCode, BizError } from '../../api/types'
import {
  petsApi,
  type PetHealthChange,
  type PetHealthDetail,
  type StorePetDetail,
} from '../../api/pets'
import {
  HEALTH_UNAVAILABLE_TEXT,
  PET_FORBIDDEN_CODE,
  createLatestOnly,
  formatHealthUpdatedAt,
  hasHealthSnapshot,
  healthChangeFieldLabel,
  healthDraftFrom,
  healthErrorText,
  healthHistoryErrorText,
  healthOperatorLabel,
  healthSnapshotFieldText,
  healthSnapshotView,
  healthUpdateErrorText,
  validateHealthDraft,
  withHealthVersion,
  type HealthChangeField,
  type HealthDraft,
  type HealthDraftErrors,
  type LatestOnlyGate,
} from '../../lib/petManage'
import { cx } from '../../lib/cx'

type Block =
  | { state: 'loading' }
  | { state: 'error'; message: string; code: number }
  | { state: 'ready'; data: StorePetDetail }

type HistoryBlock =
  | { state: 'idle' }
  | { state: 'loading' }
  | { state: 'error'; message: string; code: number }
  | { state: 'ready'; items: PetHealthChange[] }

type Mode = 'read' | 'edit' | 'history'

/** 抽屉只依赖宠物主语本身：列表页传完整 PetItem，遛狗队列传队列里的 { id, name } 即可。 */
type HealthDrawerPet = { id: string; name: string }

const TEXTAREA = 'w-full resize-y rounded-lg bg-white px-3 py-2 text-xs leading-relaxed text-ink ring-1 placeholder:text-muted focus:outline-none'
const TEXTAREA_OK = `${TEXTAREA} ring-line focus:ring-blue`
const TEXTAREA_BAD = `${TEXTAREA} ring-[#E8506B] focus:ring-[#E8506B]`

/**
 * 健康档案抽屉：读 → 改 → 读，另有只读变更历史。
 *
 * 数据链：GET /store/pets/{id} 回显；PUT /store/pets/{id}/health 保存；
 * GET /store/pets/{id}/health-history 读取 append-only 日志。
 * 门店/宠物/任务范围与操作人全部由后端从登录主体判定，本组件不传 storeId/employeeId。
 */
export default function PetHealthDrawer({
  pet,
  storeId,
  canView,
  canManage,
  onClose,
  onChanged,
}: {
  pet: HealthDrawerPet
  storeId: string
  canView: boolean
  canManage: boolean
  onClose: () => void
  onChanged?: () => void
}) {
  const [block, setBlock] = useState<Block>({ state: 'loading' })
  const [mode, setMode] = useState<Mode>('read')
  const [draft, setDraft] = useState<HealthDraft | null>(null)
  const [draftErrors, setDraftErrors] = useState<HealthDraftErrors>({})
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState('')
  const [conflict, setConflict] = useState(false)
  const [notice, setNotice] = useState('')
  const [history, setHistory] = useState<HistoryBlock>({ state: 'idle' })
  const [tick, setTick] = useState(0)

  const gateRef = useRef<LatestOnlyGate | null>(null)
  if (!gateRef.current) gateRef.current = createLatestOnly()
  const gate = gateRef.current
  const historyGateRef = useRef<LatestOnlyGate | null>(null)
  if (!historyGateRef.current) historyGateRef.current = createLatestOnly()
  const historyGate = historyGateRef.current
  const saveAcRef = useRef<AbortController | null>(null)
  const savingRef = useRef(false)
  const expectedVersionRef = useRef<string | null>(null)
  const noticeAfterReloadRef = useRef<string | null>(null)
  const historyAcRef = useRef<AbortController | null>(null)
  const aliveRef = useRef(true)
  const petIdRef = useRef(pet.id)
  petIdRef.current = pet.id

  useEffect(() => {
    aliveRef.current = true
    return () => {
      aliveRef.current = false
      saveAcRef.current?.abort()
      historyAcRef.current?.abort()
    }
  }, [])

  useEffect(() => {
    const isLatest = gate.begin()
    const ac = new AbortController()
    setBlock({ state: 'loading' })
    setMode('read')
    setDraft(null)
    setDraftErrors({})
    setSaveError('')
    setConflict(false)
    setNotice('')
    setHistory({ state: 'idle' })
    setSaving(false)
    savingRef.current = false
    expectedVersionRef.current = null
    saveAcRef.current?.abort()
    saveAcRef.current = null
    historyGate.invalidate()
    historyAcRef.current?.abort()
    historyAcRef.current = null
    petsApi.detail(pet.id, { signal: ac.signal }).then(
      (data) => {
        if (ac.signal.aborted || !isLatest()) return
        setBlock({ state: 'ready', data })
        const pending = noticeAfterReloadRef.current
        noticeAfterReloadRef.current = null
        if (pending) setNotice(pending)
      },
      (err: unknown) => {
        if (ac.signal.aborted || !isLatest()) return
        const biz = err instanceof BizError ? err : null
        setBlock({
          state: 'error',
          message: healthErrorText(biz?.code ?? -1, biz?.message ?? ''),
          code: biz?.code ?? -1,
        })
      },
    )
    return () => {
      ac.abort()
      gate.invalidate()
    }
  }, [gate, historyGate, pet.id, storeId, tick])

  const health = block.state === 'ready' ? block.data.health : null
  const canEdit = canView && canManage && block.state === 'ready'

  const startEdit = () => {
    if (savingRef.current) return
    expectedVersionRef.current = health?.updatedAt ?? null
    setDraft(healthDraftFrom(health))
    setDraftErrors({})
    setSaveError('')
    setConflict(false)
    setNotice('')
    setMode('edit')
    setHistory({ state: 'idle' })
    historyAcRef.current?.abort()
    historyAcRef.current = null
    historyGate.invalidate()
  }

  const cancelEdit = () => {
    if (savingRef.current) return
    setDraft(null)
    setDraftErrors({})
    setSaveError('')
    setConflict(false)
    setMode('read')
  }

  const save = async () => {
    if (savingRef.current || conflict || !draft) return
    const validation = validateHealthDraft(draft)
    setDraftErrors(validation.errors)
    if (!validation.body) {
      setSaveError('')
      return
    }
    const petId = pet.id
    const ac = new AbortController()
    saveAcRef.current = ac
    savingRef.current = true
    setSaving(true)
    setSaveError('')
    setConflict(false)
    setNotice('')
    try {
      const updated = await petsApi.updateHealth(
        petId,
        withHealthVersion(validation.body, expectedVersionRef.current),
        { signal: ac.signal },
      )
      if (!aliveRef.current || petIdRef.current !== petId) return
      // 先用服务端返回的真实健康块替换，再后台重拉详情（summary 等由服务端重新计算）。
      setBlock((prev) => (prev.state === 'ready'
        ? { state: 'ready', data: { ...prev.data, health: updated } }
        : prev))
      setMode('read')
      setDraft(null)
      setDraftErrors({})
      setNotice('健康档案已保存')
      setHistory({ state: 'idle' })
      historyGate.invalidate()
      onChanged?.()
      petsApi.detail(petId).then(
        (fresh) => {
          if (!aliveRef.current || petIdRef.current !== petId) return
          setBlock({ state: 'ready', data: fresh })
        },
        () => {
          /* 保存已成功；重拉失败时保留服务端刚返回的健康块，不回滚成功状态 */
        },
      )
    } catch (err) {
      if (!aliveRef.current || petIdRef.current !== petId || ac.signal.aborted) return
      const biz = err instanceof BizError ? err : null
      setConflict(biz?.code === BizCode.RATE_LIMITED)
      setSaveError(healthUpdateErrorText(biz?.code ?? -1, biz?.message ?? ''))
    } finally {
      if (saveAcRef.current === ac) saveAcRef.current = null
      savingRef.current = false
      if (aliveRef.current && petIdRef.current === petId) setSaving(false)
    }
  }

  const loadHistory = () => {
    if (historyAcRef.current) return
    const petId = pet.id
    const isLatest = historyGate.begin()
    const ac = new AbortController()
    historyAcRef.current = ac
    setHistory({ state: 'loading' })
    petsApi.healthHistory(petId, { signal: ac.signal }).then(
      (items) => {
        if (historyAcRef.current === ac) historyAcRef.current = null
        if (!aliveRef.current || petIdRef.current !== petId || ac.signal.aborted || !isLatest()) return
        setHistory({ state: 'ready', items })
      },
      (err: unknown) => {
        if (historyAcRef.current === ac) historyAcRef.current = null
        if (!aliveRef.current || petIdRef.current !== petId || ac.signal.aborted || !isLatest()) return
        const biz = err instanceof BizError ? err : null
        setHistory({
          state: 'error',
          message: healthHistoryErrorText(biz?.code ?? -1, biz?.message ?? ''),
          code: biz?.code ?? -1,
        })
      },
    )
  }

  const openHistory = () => {
    setMode('history')
    setSaveError('')
    if (history.state !== 'ready' && history.state !== 'loading') {
      loadHistory()
    }
  }

  const backToRead = () => {
    historyAcRef.current?.abort()
    historyAcRef.current = null
    historyGate.invalidate()
    setHistory((prev) => (prev.state === 'loading' ? { state: 'idle' } : prev))
    setMode('read')
  }

  /** 冲突后只允许重读；旧草稿直接丢弃，绝不自动叠到最新数据上。 */
  const reloadLatest = () => {
    if (savingRef.current) return
    noticeAfterReloadRef.current = '已重新读取最新档案，请再次进入编辑'
    setConflict(false)
    setSaveError('')
    setDraft(null)
    setDraftErrors({})
    setMode('read')
    setTick((t) => t + 1)
  }

  const canRetry =
    block.state === 'error' && block.code !== PET_FORBIDDEN_CODE && block.code !== BizCode.FORBIDDEN
  const canRetryHistory =
    history.state === 'error'
    && history.code !== PET_FORBIDDEN_CODE
    && history.code !== BizCode.FORBIDDEN

  const description =
    mode === 'edit'
      ? '维护过敏史、慢性疾病和特殊说明；保存后写入变更历史。'
      : mode === 'history'
        ? '只读变更日志；仅展示本功能上线后的真实写入，不由当前档案推导。'
        : '查看当前健康快照；疫苗、诊疗、处方和检查报告不在本页。'

  return (
    <Drawer
      open
      title={`健康档案 · ${pet.name}`}
      description={description}
      onClose={onClose}
      width="sm:max-w-[600px]"
      footer={
        mode === 'edit' ? (
          <>
            <button
              type="button"
              disabled={saving}
              onClick={cancelEdit}
              className="h-10 rounded-xl bg-white px-4 text-sm font-semibold text-ink ring-1 ring-line transition hover:border-blue hover:text-blue disabled:opacity-60"
            >
              取消
            </button>
            <button
              type="button"
              disabled={saving || conflict}
              onClick={() => void save()}
              className="flex h-10 items-center gap-2 rounded-xl bg-[#1E3A5F] px-5 text-sm font-bold text-white transition hover:bg-[#16304F] disabled:cursor-not-allowed disabled:opacity-50"
            >
              {saving ? '保存中…' : '保存健康档案'}
            </button>
          </>
        ) : mode === 'history' ? (
          <button
            type="button"
            onClick={backToRead}
            className="h-10 rounded-xl bg-white px-4 text-sm font-semibold text-ink ring-1 ring-line transition hover:border-blue hover:text-blue"
          >
            返回当前档案
          </button>
        ) : (
          <button
            type="button"
            onClick={onClose}
            className="h-10 rounded-xl bg-white px-4 text-sm font-semibold text-ink ring-1 ring-line transition hover:border-blue hover:text-blue"
          >
            关闭
          </button>
        )
      }
    >
      {block.state === 'loading' && (
        <div className="flex flex-col items-center gap-2 py-14 text-xs text-muted" role="status">
          <Spinner /> 正在读取健康档案…
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

      {block.state === 'ready' && mode === 'read' && (
        hasHealthSnapshot(health)
          ? <HealthContent health={health} canView={canView} canEdit={canEdit} notice={notice} onEdit={startEdit} onHistory={openHistory} />
          : (
            <div className="flex flex-col gap-4">
              {notice && <SaveNotice text={notice} />}
              <HealthUnavailable />
              <div className="flex flex-wrap justify-center gap-2">
                {canEdit && (
                  <button type="button" onClick={startEdit} className={ACTION_PRIMARY}>
                    <IconEdit className="h-3.5 w-3.5" /> 编辑健康档案
                  </button>
                )}
                {canView && (
                  <button type="button" onClick={openHistory} className={ACTION_SECONDARY}>
                    <IconClock className="h-3.5 w-3.5" /> 变更历史
                  </button>
                )}
              </div>
            </div>
          )
      )}

      {block.state === 'ready' && mode === 'edit' && draft && (
        <HealthEditForm
          draft={draft}
          errors={draftErrors}
          saving={saving}
          conflict={conflict}
          topError={saveError}
          onReloadLatest={reloadLatest}
          onChange={(key, value) => {
            setDraft((prev) => (prev ? { ...prev, [key]: value } : prev))
            setDraftErrors((prev) => ({ ...prev, [key]: undefined }))
            setSaveError('')
          }}
        />
      )}

      {block.state === 'ready' && mode === 'history' && (
        <HealthHistoryView
          block={history}
          canRetry={canRetryHistory}
          onRetry={loadHistory}
        />
      )}
    </Drawer>
  )
}

const ACTION_PRIMARY =
  'inline-flex h-8 items-center gap-1.5 rounded-lg bg-[#1E3A5F] px-3 text-xs font-bold text-white transition hover:bg-[#16304F]'
const ACTION_SECONDARY =
  'inline-flex h-8 items-center gap-1.5 rounded-lg bg-white px-3 text-xs font-semibold text-ink ring-1 ring-line transition hover:border-blue hover:text-blue'

function SaveNotice({ text }: { text: string }) {
  return (
    <div className="flex items-center gap-1.5 rounded-lg bg-emerald-50 px-3 py-2 text-[11px] font-semibold text-emerald-700 ring-1 ring-emerald-100">
      <IconCheckCircle className="h-3.5 w-3.5" /> {text}
    </div>
  )
}

export function HealthContent({
  health,
  canView,
  canEdit,
  notice,
  onEdit,
  onHistory,
}: {
  health: PetHealthDetail
  canView: boolean
  canEdit: boolean
  notice: string
  onEdit: () => void
  onHistory: () => void
}) {
  const view = healthSnapshotView(health)
  return (
    <div className="flex flex-col gap-5">
      {notice && <SaveNotice text={notice} />}

      <div className="flex items-start gap-2.5 rounded-xl bg-blue-mist/60 px-3.5 py-3 text-[11px] leading-relaxed text-muted">
        <IconClipboard className="mt-0.5 h-4 w-4 shrink-0 text-blue" />
        <span>这是宠物健康档案的当前快照。保存后会记录操作人、时间和前后值；疫苗明细、诊疗病历、处方和检查报告不在本页展示。</span>
      </div>

      <div className="flex flex-wrap gap-2">
        {canEdit && (
          <button type="button" onClick={onEdit} className={ACTION_PRIMARY}>
            <IconEdit className="h-3.5 w-3.5" /> 编辑健康档案
          </button>
        )}
        {canView && (
          <button type="button" onClick={onHistory} className={ACTION_SECONDARY}>
            <IconClock className="h-3.5 w-3.5" /> 变更历史
          </button>
        )}
      </div>

      <HealthTagSection title="过敏信息" items={view.allergies} empty="暂未填写过敏信息" />
      <HealthTagSection title="慢性疾病" items={view.chronicDiseases} empty="暂未填写慢性疾病信息" />

      <section>
        <h3 className="text-xs font-bold text-ink">特殊备注</h3>
        <p className="mt-1.5 whitespace-pre-line rounded-xl bg-warmgray/70 px-3.5 py-3 text-xs leading-relaxed text-ink">
          {view.specialNotes || '暂无特殊备注'}
        </p>
      </section>

      <section className="border-t border-line pt-4">
        <dl className="grid grid-cols-1 gap-2.5 text-xs sm:grid-cols-2">
          <div>
            <dt className="text-[11px] text-muted">最近更新时间</dt>
            <dd className="mt-0.5 font-semibold text-ink">{view.updatedAt}</dd>
          </div>
          <div>
            <dt className="text-[11px] text-muted">最近更新角色</dt>
            <dd className="mt-0.5 font-semibold text-ink">{view.updatedByRole}</dd>
          </div>
        </dl>
      </section>
    </div>
  )
}

export function HealthEditForm({
  draft,
  errors,
  saving,
  conflict,
  topError,
  onReloadLatest,
  onChange,
}: {
  draft: HealthDraft
  errors: HealthDraftErrors
  saving: boolean
  conflict: boolean
  topError: string
  onReloadLatest: () => void
  onChange: (key: keyof HealthDraft, value: string) => void
}) {
  const locked = saving || conflict
  return (
    <div className="flex flex-col gap-4">
      <div className="rounded-xl bg-warmgray/70 px-3.5 py-3 text-[11px] leading-relaxed text-muted">
        全部字段按“整体覆盖”保存：清空某项并保存即为删除该内容。每行一项，空行会被忽略。
      </div>

      {conflict && (
        <div className="rounded-xl bg-amber-50 px-3.5 py-3 text-[11px] leading-relaxed text-amber-900 ring-1 ring-amber-200" role="alert">
          <p className="font-semibold">健康档案已被其他操作更新。</p>
          <p className="mt-1">当前草稿基于旧版本，不能继续保存；旧草稿也不会自动覆盖最新数据。</p>
          <button
            type="button"
            onClick={onReloadLatest}
            className="mt-2 h-8 rounded-lg bg-white px-3 text-xs font-bold text-amber-900 ring-1 ring-amber-300 transition hover:bg-amber-100"
          >
            重新读取最新档案
          </button>
        </div>
      )}

      <Field label="过敏史（≤20 项，每项≤100 字）" error={errors.allergiesText}>
        <textarea
          value={draft.allergiesText}
          onChange={(e) => onChange('allergiesText', e.target.value)}
          rows={4}
          placeholder={'每行一项，如：\n花生\n海鲜'}
          aria-label="过敏史"
          aria-invalid={!!errors.allergiesText}
          disabled={locked}
          className={cx(errors.allergiesText ? TEXTAREA_BAD : TEXTAREA_OK, 'min-h-[92px]')}
        />
      </Field>

      <Field label="慢性疾病（≤20 项，每项≤100 字）" error={errors.chronicDiseasesText}>
        <textarea
          value={draft.chronicDiseasesText}
          onChange={(e) => onChange('chronicDiseasesText', e.target.value)}
          rows={4}
          placeholder={'每行一项，如：\n轻度髌骨脱位'}
          aria-label="慢性疾病"
          aria-invalid={!!errors.chronicDiseasesText}
          disabled={locked}
          className={cx(errors.chronicDiseasesText ? TEXTAREA_BAD : TEXTAREA_OK, 'min-h-[92px]')}
        />
      </Field>

      <Field label="特殊说明（≤500 字）" error={errors.specialNotes}>
        <textarea
          value={draft.specialNotes}
          onChange={(e) => onChange('specialNotes', e.target.value)}
          rows={4}
          maxLength={500}
          placeholder="如：怕吹风机；对某类药物过敏"
          aria-label="特殊说明"
          aria-invalid={!!errors.specialNotes}
          disabled={locked}
          className={cx(errors.specialNotes ? TEXTAREA_BAD : TEXTAREA_OK, 'min-h-[92px]')}
        />
        <span className="self-end text-[10px] text-muted">{draft.specialNotes.trim().length}/500</span>
      </Field>

      {topError && <p className="text-[11px] text-[#E8506B]">{topError}</p>}
      <p className="text-[10px] leading-relaxed text-muted">
        当前输入尚未提交；点击「取消」或关闭抽屉会丢弃草稿。保存期间按钮会锁定，防止重复提交。
        若保存时档案已被他人更新，请先重新读取最新档案，再重新编辑。
      </p>
    </div>
  )
}

export function HealthHistoryView({
  block,
  canRetry,
  onRetry,
}: {
  block: HistoryBlock
  canRetry: boolean
  onRetry: () => void
}) {
  if (block.state === 'idle' || block.state === 'loading') {
    return (
      <div className="flex flex-col items-center gap-2 py-14 text-xs text-muted" role="status">
        <Spinner /> 正在读取变更历史…
      </div>
    )
  }
  if (block.state === 'error') {
    return (
      <div className="flex flex-col items-center gap-3 py-12 text-center">
        <p className="text-xs leading-relaxed text-[#E8506B]">{block.message}</p>
        {canRetry && (
          <button
            type="button"
            onClick={onRetry}
            className="h-8 rounded-lg border border-line bg-white px-4 text-xs font-semibold text-ink transition hover:border-blue hover:text-blue"
          >
            重试
          </button>
        )}
      </div>
    )
  }
  if (block.items.length === 0) {
    return (
      <div className="flex flex-col items-center gap-3 py-12 text-center">
        <span className="grid h-11 w-11 place-items-center rounded-full bg-warmgray text-muted">
          <IconPaw className="h-5 w-5" />
        </span>
        <p className="text-xs font-semibold text-ink">暂无健康档案变更记录</p>
        <p className="max-w-[320px] text-[11px] leading-relaxed text-muted">
          只记录本功能上线后真实发生的编辑；历史存量不回填，也不用当前值推导。
        </p>
      </div>
    )
  }
  return (
    <div className="flex flex-col gap-3">
      <div className="rounded-xl bg-blue-mist/60 px-3.5 py-3 text-[11px] leading-relaxed text-muted">
        最近 {block.items.length} 条真实变更（最多 50 条），最新在前。每行显示变更字段的前后值；未变更字段不展示。
      </div>
      {block.items.map((item) => (
        <HistoryCard key={item.id} item={item} />
      ))}
    </div>
  )
}

function HistoryCard({ item }: { item: PetHealthChange }) {
  const fields = item.changedFields.length > 0
    ? item.changedFields
    : []
  return (
    <article className="min-w-0 overflow-hidden rounded-xl bg-white p-3.5 ring-1 ring-line">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <p className="text-[12px] font-bold text-ink">{formatHealthUpdatedAt(item.changedAt)}</p>
        <p className="text-[11px] text-muted">{healthOperatorLabel(item)}</p>
      </div>
      {fields.length === 0 ? (
        <p className="mt-2 text-[11px] text-muted">该次记录未解析到变更字段。</p>
      ) : (
        <dl className="mt-3 flex flex-col gap-2.5">
          {fields.map((field) => {
            const key = field as HealthChangeField
            return (
              <div key={field} className="rounded-lg bg-warmgray/60 px-2.5 py-2 text-[11px] leading-relaxed">
                <dt className="font-semibold text-ink">{healthChangeFieldLabel(field)}</dt>
                <dd className="mt-1 flex flex-col gap-1 text-muted sm:flex-row sm:items-start sm:gap-2">
                  <span className="min-w-0 break-words">
                    <span className="font-semibold text-muted">变更前：</span>
                    {healthSnapshotFieldText(item.before, key)}
                  </span>
                  <span className="hidden shrink-0 text-muted sm:inline" aria-hidden="true">→</span>
                  <span className="min-w-0 break-words">
                    <span className="font-semibold text-ink">变更后：</span>
                    {healthSnapshotFieldText(item.after, key)}
                  </span>
                </dd>
              </div>
            )
          })}
        </dl>
      )}
    </article>
  )
}

function HealthTagSection({ title, items, empty }: { title: string; items: string[]; empty: string }) {
  return (
    <section>
      <div className="flex items-center gap-2">
        <h3 className="text-xs font-bold text-ink">{title}</h3>
        <span className="text-[11px] text-muted">{items.length} 项</span>
      </div>
      {items.length === 0 ? (
        <p className="mt-1.5 text-xs text-muted">{empty}</p>
      ) : (
        <ul className="mt-2 flex flex-wrap gap-1.5">
          {items.map((item, index) => (
            <li
              key={`${item}-${index}`}
              className="rounded-full bg-blue-soft px-2.5 py-1 text-[11px] font-medium text-blue-deep ring-1 ring-blue/15"
            >
              {item}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function HealthUnavailable() {
  return (
    <div className="flex flex-col items-center gap-3 py-12 text-center">
      <span className="grid h-11 w-11 place-items-center rounded-full bg-warmgray text-muted">
        <IconPaw className="h-5 w-5" />
      </span>
      <p className="max-w-[340px] text-xs leading-relaxed text-muted">{HEALTH_UNAVAILABLE_TEXT}</p>
    </div>
  )
}

function Field({
  label,
  error,
  children,
}: {
  label: string
  error?: string
  children: ReactNode
}) {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-xs font-semibold text-ink">{label}</span>
      {children}
      {error && <span className="text-[11px] text-[#E8506B]">{error}</span>}
    </label>
  )
}

function Spinner() {
  return (
    <span
      className={cx('inline-block h-4 w-4 animate-spin rounded-full border-2 border-blue border-t-transparent')}
      aria-hidden="true"
    />
  )
}
