import { useEffect, useRef, useState, type ReactNode } from 'react'
import Drawer from '../ui/Drawer'
import { useToast } from '../ui/Toast'
import { BizError } from '../../api/types'
import {
  PET_STATUS_LABEL,
  petsApi,
  type PetItem,
  type PetProfile,
  type StorePetDetail,
} from '../../api/pets'
import {
  buildPetEditBody,
  createLatestOnly,
  petEditErrorText,
  toPetEditDraft,
  todayISO,
  validatePetEditDraft,
  type LatestOnlyGate,
  type PetEditDraft,
  type PetEditErrors,
} from '../../lib/petManage'
import { cx } from '../../lib/cx'

type Block =
  | { state: 'loading' }
  | { state: 'error'; message: string; code: number }
  | { state: 'ready'; data: StorePetDetail }

const INPUT = 'h-10 w-full rounded-lg bg-white px-3 text-sm text-ink ring-1 placeholder:text-muted focus:outline-none'
const INPUT_OK = `${INPUT} ring-line focus:ring-blue`
const INPUT_BAD = `${INPUT} ring-[#E8506B] focus:ring-[#E8506B]`

/**
 * B 端宠物基础资料编辑（PUT /store/pets/{id}）。
 * 详情通过 GET /store/pets/{id} 回显；只提交受限字段，头像/状态/归属/健康档案不提供控件。
 */
export default function PetEditDrawer({
  pet,
  storeId,
  onClose,
  onSaved,
}: {
  pet: PetItem
  storeId: string
  onClose: () => void
  onSaved: (saved: PetProfile) => void
}) {
  const { push } = useToast()
  const [block, setBlock] = useState<Block>({ state: 'loading' })
  const [draft, setDraft] = useState<PetEditDraft | null>(null)
  const [errors, setErrors] = useState<PetEditErrors>({})
  const [topError, setTopError] = useState('')
  const [saving, setSaving] = useState(false)
  const [tick, setTick] = useState(0)
  const gateRef = useRef<LatestOnlyGate | null>(null)
  if (!gateRef.current) gateRef.current = createLatestOnly()
  const gate = gateRef.current

  useEffect(() => {
    const isLatest = gate.begin()
    const ac = new AbortController()
    setBlock({ state: 'loading' })
    setDraft(null)
    petsApi.detail(pet.id, { signal: ac.signal }).then(
      (data) => {
        if (ac.signal.aborted || !isLatest()) return
        setDraft(toPetEditDraft(data.pet))
        setBlock({ state: 'ready', data })
        setErrors({})
        setTopError('')
      },
      (err: unknown) => {
        if (ac.signal.aborted || !isLatest()) return
        const biz = err instanceof BizError ? err : null
        setBlock({
          state: 'error',
          message: petEditErrorText(biz?.code ?? -1, biz?.message ?? ''),
          code: biz?.code ?? -1,
        })
      },
    )
    return () => {
      ac.abort()
      gate.invalidate()
    }
  }, [gate, pet.id, storeId, tick])

  const set = <K extends keyof PetEditDraft>(key: K, value: PetEditDraft[K]) => {
    setDraft((prev) => (prev ? { ...prev, [key]: value } : prev))
    setErrors((prev) => ({ ...prev, [key]: undefined }))
    setTopError('')
  }

  const save = async () => {
    if (saving || !draft) return
    const next = validatePetEditDraft(draft)
    setErrors(next)
    if (Object.keys(next).length > 0) {
      push('还有几项需要修正', 'error')
      return
    }
    setSaving(true)
    setTopError('')
    try {
      const saved = await petsApi.update(pet.id, buildPetEditBody(draft))
      push('宠物资料已保存', 'success')
      onSaved(saved)
    } catch (err) {
      const biz = err instanceof BizError ? err : null
      setTopError(petEditErrorText(biz?.code ?? 0, biz?.message ?? ''))
    } finally {
      setSaving(false)
    }
  }

  const canRetry =
    block.state === 'error' && block.code !== 20101 && block.code !== 10003 && block.code !== 10004

  return (
    <Drawer
      open
      title={`编辑资料 · ${pet.name}`}
      description="只修改宠物基础资料；头像、状态、客户归属和健康档案不在此修改。"
      onClose={onClose}
      width="sm:max-w-[560px]"
      footer={
        block.state === 'ready' ? (
          <>
            <button
              type="button"
              disabled={saving}
              onClick={onClose}
              className="h-10 rounded-xl bg-white px-4 text-sm font-semibold text-ink ring-1 ring-line transition hover:border-blue hover:text-blue disabled:opacity-60"
            >
              取消
            </button>
            <button
              type="button"
              disabled={saving}
              onClick={() => void save()}
              className="flex h-10 items-center gap-2 rounded-xl bg-[#1E3A5F] px-5 text-sm font-bold text-white transition hover:bg-[#16304F] disabled:cursor-not-allowed disabled:opacity-50"
            >
              {saving ? '保存中…' : '保存修改'}
            </button>
          </>
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
          <Spinner /> 正在读取宠物资料…
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

      {block.state === 'ready' && draft && (
        <div className="flex flex-col gap-4">
          <div className="rounded-xl bg-blue-mist/60 px-3.5 py-3 text-[11px] leading-relaxed text-muted">
            当前状态：<span className="font-semibold text-ink">{PET_STATUS_LABEL[block.data.pet.status] ?? block.data.pet.status}</span>
            。体型由服务端按体重计算；头像、客户归属、宠物状态和健康档案请在对应流程维护。
          </div>

          <Field label="宠物昵称" required error={errors.name}>
            <input
              value={draft.name}
              onChange={(e) => set('name', e.target.value)}
              maxLength={50}
              aria-label="宠物昵称"
              aria-invalid={!!errors.name}
              className={errors.name ? INPUT_BAD : INPUT_OK}
            />
          </Field>

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label="物种" required error={errors.species}>
              <select
                value={draft.species}
                onChange={(e) => set('species', e.target.value as PetEditDraft['species'])}
                aria-label="物种"
                className={errors.species ? INPUT_BAD : INPUT_OK}
              >
                <option value="DOG">犬</option>
                <option value="CAT">猫</option>
                <option value="OTHER">其他</option>
              </select>
            </Field>
            <Field label="品种" error={errors.breed}>
              <input
                value={draft.breed}
                onChange={(e) => set('breed', e.target.value)}
                maxLength={50}
                placeholder="如：柴犬"
                aria-label="品种"
                className={errors.breed ? INPUT_BAD : INPUT_OK}
              />
            </Field>
            <Field label="性别" error={errors.gender}>
              <select
                value={draft.gender}
                onChange={(e) => set('gender', e.target.value as PetEditDraft['gender'])}
                aria-label="性别"
                className={errors.gender ? INPUT_BAD : INPUT_OK}
              >
                <option value="">未登记 / 保留原值</option>
                <option value="UNKNOWN">未知</option>
                <option value="MALE">公</option>
                <option value="FEMALE">母</option>
              </select>
            </Field>
            <Field label="是否绝育" error={errors.isNeutered}>
              <select
                value={draft.isNeutered === null ? '' : draft.isNeutered ? 'YES' : 'NO'}
                onChange={(e) => set('isNeutered', e.target.value === '' ? null : e.target.value === 'YES')}
                aria-label="是否绝育"
                className={errors.isNeutered ? INPUT_BAD : INPUT_OK}
              >
                <option value="">未登记 / 保留原值</option>
                <option value="YES">已绝育</option>
                <option value="NO">未绝育</option>
              </select>
            </Field>
            <Field label="生日" error={errors.birthday}>
              <input
                type="date"
                value={draft.birthday}
                max={todayISO()}
                onChange={(e) => {
                  set('birthday', e.target.value)
                  if (!e.target.value) set('birthdayApprox', false)
                }}
                aria-label="生日"
                className={errors.birthday ? INPUT_BAD : INPUT_OK}
              />
            </Field>
            <Field label="体重（kg）" error={errors.weightKg}>
              <input
                value={draft.weightKg}
                onChange={(e) => set('weightKg', e.target.value)}
                inputMode="decimal"
                placeholder="如：5.5"
                aria-label="体重"
                aria-invalid={!!errors.weightKg}
                className={errors.weightKg ? INPUT_BAD : INPUT_OK}
              />
            </Field>
          </div>

          <label className="flex items-center gap-2 px-1 text-xs text-muted">
            <input
              type="checkbox"
              checked={draft.birthdayApprox}
              disabled={!draft.birthday}
              onChange={(e) => set('birthdayApprox', e.target.checked)}
              className="h-4 w-4 accent-navy"
            />
            生日是大概的
          </label>

          <Field label="备注（≤500 字）" error={errors.remark}>
            <textarea
              value={draft.remark}
              onChange={(e) => set('remark', e.target.value)}
              maxLength={500}
              rows={3}
              placeholder="如：怕吹风机，需要温柔安抚"
              aria-label="宠物备注"
              aria-invalid={!!errors.remark}
              className={cx(errors.remark ? INPUT_BAD : INPUT_OK, 'h-20 resize-none py-2 leading-relaxed')}
            />
          </Field>

          {topError && <p className="text-[11px] text-[#E8506B]">{topError}</p>}
        </div>
      )}
    </Drawer>
  )
}

function Field({
  label,
  required,
  error,
  children,
}: {
  label: string
  required?: boolean
  error?: string
  children: ReactNode
}) {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-xs font-semibold text-ink">
        {label}
        {required && <span className="text-[#E8506B]"> *</span>}
      </span>
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
