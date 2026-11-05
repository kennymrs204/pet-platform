import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { BizCode, BizError } from '../api/types'
import { PetForm, PetGender, PetSpecies, PetStatus, petApi } from '../api/pet'
import { tokenStore } from '../auth/tokenStore'
import { useBizFail } from '../auth/useBizFail'
import PageHeader from '../components/layout/PageHeader'
import AvatarPicker from '../components/ui/AvatarPicker'
import { useToast } from '../components/ui/Toast'
import { cx } from '../lib/cx'
import { SPECIES_LABEL } from '../lib/petText'

const SPECIES_OPTS: PetSpecies[] = ['DOG', 'CAT', 'OTHER']
const GENDER_OPTS: { key: PetGender; label: string }[] = [
  { key: 'MALE', label: '公 ♂' },
  { key: 'FEMALE', label: '母 ♀' },
  { key: 'UNKNOWN', label: '暂不填' },
]

interface FormState {
  name: string
  species: PetSpecies
  breed: string
  gender: PetGender | ''
  birthday: string
  birthdayApprox: boolean
  weightKg: string
  neutered: 'YES' | 'NO' | ''
  remark: string
  /** 对象存储 key（ADR-029 presigned 直传回填），不是 URL */
  avatar: string | null
}

const EMPTY: FormState = {
  name: '',
  species: 'DOG',
  breed: '',
  gender: '',
  birthday: '',
  birthdayApprox: false,
  weightKg: '',
  neutered: '',
  remark: '',
  avatar: null,
}

/** 校验规则严格对齐后端 @Size/@Pattern/@DecimalMin，避免提交后才报错。 */
function validate(s: FormState): Partial<Record<keyof FormState, string>> {
  const e: Partial<Record<keyof FormState, string>> = {}
  if (!s.name.trim()) e.name = '请填写宠物昵称'
  else if (s.name.trim().length > 50) e.name = '昵称不超过 50 字'
  if (s.breed.length > 50) e.breed = '品种不超过 50 字'
  if (s.weightKg) {
    const n = Number(s.weightKg)
    if (!Number.isFinite(n) || n <= 0) e.weightKg = '体重需为大于 0 的数字（kg）'
    else if (n > 999.99) e.weightKg = '体重超出可录入范围（≤999.99kg）'
  }
  if (s.birthday && s.birthday > todayISO()) e.birthday = '生日不能晚于今天'
  if (s.remark.length > 500) e.remark = '备注不超过 500 字'
  return e
}

function todayISO(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/**
 * 宠物建档 / 编辑（POST 与 PUT /customer/pets[/{id}]）。
 * PUT 是全量更新，故编辑态先 GET 回填所有字段；sizeCategory 与 vaccineSummary 由服务端维护，表单不接受。
 */
export default function PetFormPage({ mode }: { mode: 'create' | 'edit' }) {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const { push } = useToast()
  const fail = useBizFail()
  const userId = tokenStore.getUserId()

  const [s, setS] = useState<FormState>(EMPTY)
  const [errors, setErrors] = useState<Partial<Record<keyof FormState, string>>>({})
  const [avatarUrl, setAvatarUrl] = useState<string | null>(null)
  const [status, setStatus] = useState<PetStatus>('NORMAL')
  const [loading, setLoading] = useState(mode === 'edit')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!userId) navigate('/login', { replace: true })
  }, [userId, navigate])

  useEffect(() => {
    if (mode !== 'edit' || !id) return
    const ac = new AbortController()
    petApi
      .detail(id)
      .then((p) => {
        if (ac.signal.aborted) return
        setLoading(false)
        setS({
          name: p.name,
          species: p.species,
          breed: p.breed ?? '',
          gender: p.gender ?? '',
          birthday: p.birthday ?? '',
          birthdayApprox: p.birthdayApprox ?? false,
          weightKg: p.weightKg == null ? '' : String(p.weightKg),
          neutered: p.isNeutered == null ? '' : p.isNeutered ? 'YES' : 'NO',
          remark: p.remark ?? '',
          avatar: p.avatar ?? null,
        })
        setAvatarUrl(p.avatarUrl ?? null)
        setStatus(p.status)
      })
      .catch((e) => {
        if (ac.signal.aborted) return
        fail(e)
        navigate('/pets', { replace: true })
      })
    return () => ac.abort()
  }, [mode, id, fail, navigate])

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    setS((prev) => ({ ...prev, [key]: value }))
    setErrors((prev) => ({ ...prev, [key]: undefined }))
  }

  const previewWeight = useMemo(() => (s.weightKg ? Number(s.weightKg) : null), [s.weightKg])

  const submit = async () => {
    const e = validate(s)
    setErrors(e)
    if (Object.keys(e).length > 0) {
      push('还有几项需要修正', 'error')
      return
    }
    const body: PetForm = {
      name: s.name.trim(),
      species: s.species,
      breed: s.breed.trim() || null,
      gender: s.gender || null,
      isNeutered: s.neutered === '' ? null : s.neutered === 'YES',
      birthday: s.birthday || null,
      birthdayApprox: s.birthday ? s.birthdayApprox : null,
      weightKg: s.weightKg ? Number(s.weightKg) : null,
      avatar: s.avatar,
      remark: s.remark.trim() || null,
    }
    setSaving(true)
    try {
      const saved = mode === 'create' ? await petApi.create(body) : await petApi.update(id, { ...body, status })
      push(mode === 'create' ? `${saved.name} 的档案建好了` : '档案已更新', 'success')
      navigate(`/pets/${saved.id}`, { replace: mode === 'edit' })
    } catch (err) {
      // 20101：编辑目标已不属于当前账号（被删除或越权），回列表而不是停在表单上
      if (err instanceof BizError && err.code === BizCode.PET_NOT_MANAGEABLE && mode === 'edit') {
        push('这只宠物已被删除或不属于当前账号', 'error')
        navigate('/pets', { replace: true })
      } else {
        fail(err)
      }
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return (
      <main className="min-h-[100dvh] bg-brand-gradient">
        <div className="mx-auto max-w-md px-4 pb-10">
          <PageHeader title={mode === 'create' ? '添加宠物' : '编辑资料'} fallback="/pets" />
          <div className="animate-pulse space-y-4 pt-4" aria-busy="true">
            <div className="h-14 rounded-2xl bg-warmgray" />
            <div className="h-24 rounded-2xl bg-warmgray" />
            <div className="h-14 rounded-2xl bg-warmgray" />
          </div>
        </div>
      </main>
    )
  }

  return (
    <main className="min-h-[100dvh] bg-brand-gradient">
      <div className="mx-auto max-w-md px-4 pb-28">
        <PageHeader title={mode === 'create' ? '添加宠物' : '编辑资料'} fallback="/pets" />

        <div className="animate-fade-up mt-2 space-y-4">
          <div className="flex items-center gap-3">
            <AvatarPicker
              scene="PET_AVATAR"
              value={s.avatar}
              previewUrl={avatarUrl}
              onPicked={(key, localUrl) => {
                set('avatar', key)
                setAvatarUrl(localUrl)
              }}
              onError={(msg) => push(msg, 'error')}
            />
            <p className="min-w-0 flex-1 text-[11px] leading-relaxed text-muted">
              点左侧给毛孩子传一张头像，本地直传对象存储；JPG/PNG/WebP，5MB 内。
            </p>
          </div>
          <Field label="宠物昵称" required error={errors.name} hint="不超过 50 字">
            <input
              value={s.name}
              onChange={(ev) => set('name', ev.target.value)}
              placeholder="如：豆包"
              maxLength={60}
              className={inputCls(!!errors.name)}
            />
          </Field>

          <Field label="物种" required>
            <Segmented
              options={SPECIES_OPTS.map((k) => ({ key: k, label: SPECIES_LABEL[k] }))}
              value={s.species}
              onChange={(k) => set('species', k as PetSpecies)}
            />
          </Field>

          <Field label="品种" error={errors.breed} hint="留空则按物种显示">
            <input
              value={s.breed}
              onChange={(ev) => set('breed', ev.target.value)}
              placeholder="如：金毛 / 橘猫"
              maxLength={60}
              className={inputCls(!!errors.breed)}
            />
          </Field>

          <Field label="性别">
            <Segmented
              options={GENDER_OPTS.map((o) => ({ key: o.key, label: o.label }))}
              value={s.gender}
              onChange={(k) => set('gender', k as PetGender)}
            />
          </Field>

          <div className="grid grid-cols-2 gap-3">
            <Field label="生日" error={errors.birthday}>
              <input
                type="date"
                value={s.birthday}
                max={todayISO()}
                onChange={(ev) => set('birthday', ev.target.value)}
                className={inputCls(!!errors.birthday)}
              />
            </Field>
            <Field label="体重（kg）" error={errors.weightKg}>
              <input
                inputMode="decimal"
                value={s.weightKg}
                onChange={(ev) => set('weightKg', ev.target.value.replace(/[^\d.]/g, ''))}
                placeholder="如 18.5"
                className={inputCls(!!errors.weightKg)}
              />
            </Field>
          </div>

          <label className="flex items-center gap-2 px-1 text-xs text-muted">
            <input
              type="checkbox"
              checked={s.birthdayApprox}
              disabled={!s.birthday}
              onChange={(ev) => set('birthdayApprox', ev.target.checked)}
              className="h-4 w-4 accent-navy"
            />
            生日是大概的（显示时加「约」）
          </label>

          <Field label="是否绝育">
            <Segmented
              options={[
                { key: 'YES', label: '已绝育' },
                { key: 'NO', label: '未绝育' },
                { key: '', label: '暂不填' },
              ]}
              value={s.neutered}
              onChange={(k) => set('neutered', k as FormState['neutered'])}
            />
          </Field>

          <Field label="备注" error={errors.remark} hint={`${s.remark.length}/500`}>
            <textarea
              value={s.remark}
              onChange={(ev) => set('remark', ev.target.value)}
              placeholder="如：怕吹风机，需要温柔安抚"
              rows={3}
              maxLength={520}
              className={cx(inputCls(!!errors.remark), 'h-auto resize-none py-2.5 leading-relaxed')}
            />
          </Field>

          <p className="rounded-2xl bg-white/60 px-3.5 py-3 text-[11px] leading-relaxed text-muted ring-1 ring-navy/5">
            体型（小型/中型/大型）由服务端按体重判定，保存后可在档案里看到；
            头像本地直传对象存储（ADR-029），保存后档案与列表即显示。
            {previewWeight != null && Number.isFinite(previewWeight) && previewWeight > 0
              ? ` 当前录入体重 ${previewWeight}kg。`
              : ''}
          </p>
        </div>
      </div>

      <div className="fixed inset-x-0 bottom-0 z-30 border-t border-navy/5 bg-white/92 px-4 py-3 shadow-card backdrop-blur-xl">
        <div className="mx-auto flex max-w-md gap-3">
          <button
            type="button"
            onClick={() => navigate(-1)}
            className="h-12 w-28 rounded-2xl bg-warmgray text-sm font-semibold text-navy/80"
          >
            取消
          </button>
          <button
            type="button"
            disabled={saving}
            onClick={() => void submit()}
            className={cx(
              'h-12 flex-1 rounded-2xl bg-btn-gradient text-sm font-semibold text-white shadow-btn transition',
              saving ? 'opacity-60' : 'hover:opacity-95 active:scale-[0.99]',
            )}
          >
            {saving ? '保存中…' : mode === 'create' ? '建立档案' : '保存修改'}
          </button>
        </div>
      </div>
    </main>
  )
}

function inputCls(hasError: boolean): string {
  return cx(
    'w-full rounded-2xl bg-white/90 px-4 py-3 text-sm text-ink shadow-soft ring-1 outline-none transition placeholder:text-muted/60',
    hasError ? 'ring-rose-300 focus:ring-2 focus:ring-rose-300' : 'ring-navy/5 focus:ring-2 focus:ring-navy/25',
  )
}

function Field({
  label,
  required,
  error,
  hint,
  children,
}: {
  label: string
  required?: boolean
  error?: string
  hint?: string
  children: ReactNode
}) {
  return (
    <div>
      <p className="mb-1.5 flex items-center gap-1 px-1 text-xs font-semibold text-navy/80">
        {label}
        {required && <span className="text-rose-400">*</span>}
      </p>
      {children}
      {error ? (
        <p className="mt-1 px-1 text-[11px] font-medium text-rose-500">{error}</p>
      ) : hint ? (
        <p className="mt-1 px-1 text-[11px] text-muted">{hint}</p>
      ) : null}
    </div>
  )
}

function Segmented({
  options,
  value,
  onChange,
}: {
  options: { key: string; label: string }[]
  value: string
  onChange: (key: string) => void
}) {
  return (
    <div className="grid gap-2" style={{ gridTemplateColumns: `repeat(${options.length}, minmax(0, 1fr))` }}>
      {options.map((o) => {
        const on = o.key === value
        return (
          <button
            key={o.key || 'none'}
            type="button"
            aria-pressed={on}
            onClick={() => onChange(o.key)}
            className={cx(
              'rounded-2xl py-2.5 text-xs font-semibold transition',
              on ? 'bg-btn-gradient text-white shadow-btn' : 'bg-white/85 text-navy ring-1 ring-navy/5 hover:bg-white',
            )}
          >
            {o.label}
          </button>
        )
      })}
    </div>
  )
}
