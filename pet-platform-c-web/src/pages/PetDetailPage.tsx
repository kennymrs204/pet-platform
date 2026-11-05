import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { BizCode, BizError } from '../api/types'
import { PetHealthVO, PetVO, petApi } from '../api/pet'
import { tokenStore } from '../auth/tokenStore'
import { useBizFail } from '../auth/useBizFail'
import PageHeader from '../components/layout/PageHeader'
import PetAvatar from '../components/pet/PetAvatar'
import { useToast } from '../components/ui/Toast'
import { IconCalendar, IconEdit, IconPlus, IconStethoscope, IconTrash, IconX } from '../components/ui/icons'
import { cx } from '../lib/cx'
import {
  GENDER_TEXT,
  SIZE_LABEL,
  SPECIES_LABEL,
  STATUS_TEXT,
  petAgeText,
  petDeleteErrorText,
  petWeightText,
  statusBadgeTone,
} from '../lib/petText'

/**
 * 宠物详情 + 健康档案（GET/PUT /customer/pets/{id}[/health]）。
 * 健康档案单独降级：它要 pet:health:view，与基础档案是两个码，任一失败不拖垮整页。
 * 疫苗/诊疗明细属 medical 模块（未开工），此处只展示后端同步的 vaccineSummary 只读串。
 */
export default function PetDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const { push } = useToast()
  const fail = useBizFail()
  const userId = tokenStore.getUserId()

  const [pet, setPet] = useState<PetVO | null>(null)
  const [health, setHealth] = useState<PetHealthVO | null>(null)
  const [healthFailed, setHealthFailed] = useState(false)
  const [editingHealth, setEditingHealth] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [deleting, setDeleting] = useState(false)

  useEffect(() => {
    if (!userId) navigate('/login', { replace: true })
  }, [userId, navigate])

  const load = useCallback(
    (signal: AbortSignal) => {
      petApi
        .detail(id)
        .then((p) => {
          if (!signal.aborted) setPet(p)
        })
        .catch((e) => {
          if (signal.aborted) return
          if (e instanceof BizError && e.code === BizCode.PET_NOT_MANAGEABLE) {
            push('没有这只宠物的档案，可能已被删除', 'error')
            navigate('/pets', { replace: true })
            return
          }
          fail(e)
        })
      petApi
        .health(id)
        .then((h) => {
          if (!signal.aborted) setHealth(h)
        })
        .catch(() => {
          if (!signal.aborted) setHealthFailed(true)
        })
    },
    [id, fail, navigate, push],
  )

  useEffect(() => {
    if (!id) return
    const ac = new AbortController()
    load(ac.signal)
    return () => ac.abort()
  }, [id, load])

  const remove = async () => {
    setDeleting(true)
    try {
      await petApi.remove(id)
      push(`${pet?.name ?? '宠物'}的档案已删除`, 'success')
      navigate('/pets', { replace: true })
    } catch (e) {
      if (e instanceof BizError && e.code === BizCode.ILLEGAL_STATE_TRANSITION) {
        push(petDeleteErrorText(e.code, e.message), 'error')
      } else {
        fail(e)
      }
      setConfirmDelete(false)
    } finally {
      setDeleting(false)
    }
  }

  if (!pet) {
    return (
      <main className="min-h-[100dvh] bg-brand-gradient">
        <div className="mx-auto max-w-md px-4 pb-10">
          <PageHeader title="宠物档案" fallback="/pets" />
          <div className="animate-pulse space-y-4 pt-4" aria-busy="true">
            <div className="h-24 rounded-3xl bg-white/60" />
            <div className="h-40 rounded-3xl bg-white/60" />
          </div>
        </div>
      </main>
    )
  }

  return (
    <main className="min-h-[100dvh] bg-brand-gradient">
      <div className="mx-auto max-w-md px-4 pb-28">
        <PageHeader
          title="宠物档案"
          fallback="/pets"
          right={{ label: '编辑资料', onClick: () => navigate(`/pets/${id}/edit`) }}
        />

        <section className="animate-fade-up rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5">
          <div className="flex items-start gap-3">
            <PetAvatar pet={pet} className="h-16 w-16" />
            <div className="min-w-0 flex-1">
              <p className="flex items-center gap-1.5">
                <span className="truncate text-lg font-bold text-ink">{pet.name}</span>
                <span className={cx('rounded-full px-2 py-0.5 text-[11px] font-semibold', statusBadgeTone(pet.status))}>
                  {STATUS_TEXT[pet.status]}
                </span>
              </p>
              <p className="mt-1 text-xs text-muted">
                {pet.breed || SPECIES_LABEL[pet.species]} · {pet.gender ? GENDER_TEXT[pet.gender] : '性别未登记'} ·{' '}
                {petAgeText(pet.birthday, pet.birthdayApprox)}
              </p>
            </div>
          </div>

          <dl className="mt-4 grid grid-cols-2 gap-x-3 gap-y-3 border-t border-navy/5 pt-4">
            <Item label="物种" value={SPECIES_LABEL[pet.species]} />
            <Item label="体型" value={pet.sizeCategory ? SIZE_LABEL[pet.sizeCategory] : '未判定'} />
            <Item label="体重" value={petWeightText(pet.weightKg)} />
            <Item label="生日" value={pet.birthday ? `${pet.birthdayApprox ? '约 ' : ''}${pet.birthday}` : '未登记'} />
            <Item
              label="绝育"
              value={pet.isNeutered == null ? '未登记' : pet.isNeutered ? '已绝育' : '未绝育'}
            />
            <Item label="建档" value={pet.createdAt?.slice(0, 10) ?? '—'} />
          </dl>
        </section>

        <section className="animate-fade-up mt-4 rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5" style={{ animationDelay: '70ms' }}>
          <div className="flex items-center justify-between">
            <h2 className="flex items-center gap-1.5 text-sm font-bold text-ink">
              <IconStethoscope className="h-4 w-4 text-gold-dark" /> 健康档案
            </h2>
            {!editingHealth && (
              <button
                type="button"
                onClick={() => setEditingHealth(true)}
                className="flex items-center gap-1 text-xs font-semibold text-navy"
              >
                <IconEdit className="h-3.5 w-3.5" /> 编辑
              </button>
            )}
          </div>

          {editingHealth ? (
            <HealthEditor
              petId={id}
              initial={health}
              onCancel={() => setEditingHealth(false)}
              onSaved={(h) => {
                setHealth(h)
                setEditingHealth(false)
                push('健康档案已更新', 'success')
              }}
              onError={fail}
              onConflict={() => {
                setEditingHealth(false)
                petApi
                  .health(id)
                  .then((h) => {
                    setHealth(h)
                    setHealthFailed(false)
                    push('档案已被其他操作更新，已读取最新数据，请重新编辑', 'info')
                  })
                  .catch(() => setHealthFailed(true))
              }}
            />
          ) : healthFailed ? (
            <p className="mt-3 rounded-2xl bg-warmgray px-3 py-2.5 text-xs text-muted">
              健康档案拉取失败（可能无 pet:health:view 权限），基础档案不受影响。
            </p>
          ) : (
            <div className="mt-3 space-y-3">
              <TagList title="过敏项" items={health?.allergies ?? []} empty="暂无记录的过敏项" />
              <TagList title="慢性病" items={health?.chronicDiseases ?? []} empty="暂无记录的慢性病" />
              <div>
                <p className="text-[11px] font-semibold text-muted">特殊注意事项</p>
                <p className="mt-1 text-xs leading-relaxed text-ink">
                  {health?.specialNotes || '暂无补充，医生或门店登记的特殊提醒会显示在这里'}
                </p>
              </div>
              <p className="border-t border-navy/5 pt-2.5 text-[11px] text-muted">
                最近更新：{health?.updatedAt ? health.updatedAt.slice(0, 16).replace('T', ' ') : '尚无记录'}
                {health?.updatedByRole ? ` · 由 ${health.updatedByRole === 'CUSTOMER' ? '家长' : health.updatedByRole} 更新` : ''}
              </p>
            </div>
          )}
        </section>

        <section className="animate-fade-up mt-4 rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5" style={{ animationDelay: '140ms' }}>
          <h2 className="flex items-center gap-1.5 text-sm font-bold text-ink">
            <IconCalendar className="h-4 w-4 text-gold-dark" /> 疫苗与服务
          </h2>
          <p className="mt-2 text-xs leading-relaxed text-ink">
            {pet.vaccineSummary || '暂无疫苗记录'}
          </p>
          <p className="mt-2 rounded-2xl bg-ivory px-3 py-2 text-[11px] leading-relaxed text-navy/70 ring-1 ring-gold/25">
            疫苗明细、体检报告与服务记录属医疗/履约模块，后端接口尚未开放，暂不在这里展示。
          </p>
        </section>

        {pet.remark && (
          <section className="animate-fade-up mt-4 rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5" style={{ animationDelay: '210ms' }}>
            <h2 className="text-sm font-bold text-ink">备注</h2>
            <p className="mt-2 whitespace-pre-line text-xs leading-relaxed text-ink">{pet.remark}</p>
          </section>
        )}

        <div className="mt-6 space-y-2">
          <button
            type="button"
            onClick={() => navigate(`/booking?petId=${pet.id}`)}
            className="h-12 w-full rounded-2xl bg-btn-gradient text-sm font-semibold text-white shadow-btn transition hover:opacity-95 active:scale-[0.99]"
          >
            为 {pet.name} 预约服务
          </button>
          {confirmDelete ? (
            <div className="flex items-center gap-2 rounded-2xl bg-rose-50 px-3 py-2.5 ring-1 ring-rose-200/70">
              <span className="flex-1 text-[11px] leading-snug text-rose-600">
                删除后宠物档案将不再显示；已完成的服务/托管历史会保留。确定？
              </span>
              <button
                type="button"
                disabled={deleting}
                onClick={() => void remove()}
                className="shrink-0 rounded-xl bg-rose-500 px-3 py-1.5 text-xs font-semibold text-white disabled:opacity-60"
              >
                {deleting ? '删除中…' : '确定删除'}
              </button>
              <button
                type="button"
                onClick={() => setConfirmDelete(false)}
                className="shrink-0 rounded-xl bg-white px-3 py-1.5 text-xs font-semibold text-navy/70"
              >
                取消
              </button>
            </div>
          ) : (
            <button
              type="button"
              onClick={() => setConfirmDelete(true)}
              className="flex h-11 w-full items-center justify-center gap-1.5 rounded-2xl bg-white/70 text-xs font-semibold text-muted ring-1 ring-navy/5"
            >
              <IconTrash className="h-3.5 w-3.5" /> 删除宠物档案
            </button>
          )}
        </div>
      </div>
    </main>
  )
}

function Item({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-[11px] text-muted">{label}</dt>
      <dd className="mt-0.5 text-xs font-semibold text-ink">{value}</dd>
    </div>
  )
}

function TagList({ title, items, empty }: { title: string; items: string[]; empty: string }) {
  return (
    <div>
      <p className="text-[11px] font-semibold text-muted">{title}</p>
      {items.length === 0 ? (
        <p className="mt-1 text-xs text-muted/80">{empty}</p>
      ) : (
        <ul className="mt-1.5 flex flex-wrap gap-1.5">
          {items.map((t) => (
            <li key={t} className="rounded-full bg-ivory px-2.5 py-1 text-[11px] font-medium text-gold-dark ring-1 ring-gold/25">
              {t}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** 健康档案内联编辑器：数组项 ≤20、单项 ≤100 字、特殊说明 ≤500 字（对齐后端 @Size）。 */
function HealthEditor({
  petId,
  initial,
  onSaved,
  onCancel,
  onError,
  onConflict,
}: {
  petId: string
  initial: PetHealthVO | null
  onSaved: (h: PetHealthVO) => void
  onCancel: () => void
  onError: (e: unknown) => void
  onConflict: () => void
}) {
  const [allergies, setAllergies] = useState<string[]>(initial?.allergies ?? [])
  const [chronic, setChronic] = useState<string[]>(initial?.chronicDiseases ?? [])
  const [notes, setNotes] = useState(initial?.specialNotes ?? '')
  const [draft, setDraft] = useState('')
  const [target, setTarget] = useState<'allergies' | 'chronic'>('allergies')
  const [saving, setSaving] = useState(false)
  const [err, setErr] = useState('')
  const { push } = useToast()

  const add = () => {
    const v = draft.trim()
    if (!v) return
    if (v.length > 100) {
      setErr('单项不超过 100 字')
      return
    }
    const cur = target === 'allergies' ? allergies : chronic
    if (cur.length >= 20) {
      setErr('每类最多 20 项')
      return
    }
    if (cur.includes(v)) {
      setErr('这一项已经记录过了')
      return
    }
    if (target === 'allergies') setAllergies([...cur, v])
    else setChronic([...cur, v])
    setDraft('')
    setErr('')
  }

  const save = async () => {
    if (notes.length > 500) {
      setErr('特殊说明不超过 500 字')
      return
    }
    setSaving(true)
    try {
      onSaved(await petApi.updateHealth(petId, {
        allergies,
        chronicDiseases: chronic,
        specialNotes: notes.trim() || null,
        expectedUpdatedAt: initial?.updatedAt ?? null,
      }))
    } catch (e) {
      // 版本冲突：丢弃旧草稿并重读，不把旧值自动覆盖到最新档案上。
      if (e instanceof BizError && e.code === BizCode.RATE_LIMITED) {
        onConflict()
        return
      }
      onError(e)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="mt-3 space-y-3">
      <EditorTags
        title="过敏项"
        items={allergies}
        onChange={setAllergies}
        active={target === 'allergies'}
        onFocus={() => setTarget('allergies')}
      />
      <EditorTags
        title="慢性病"
        items={chronic}
        onChange={setChronic}
        active={target === 'chronic'}
        onFocus={() => setTarget('chronic')}
      />
      <div className="flex gap-2">
        <input
          value={draft}
          onChange={(e) => {
            setDraft(e.target.value)
            setErr('')
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              add()
            }
          }}
          placeholder={target === 'allergies' ? '如：鸡肉、花粉' : '如：皮肤病史'}
          className="flex-1 rounded-2xl bg-white px-3.5 py-2.5 text-sm text-ink ring-1 ring-navy/10 outline-none focus:ring-2 focus:ring-navy/25"
        />
        <button
          type="button"
          onClick={add}
          className="flex h-[42px] w-[42px] items-center justify-center rounded-2xl bg-navy text-white"
          aria-label="添加一项"
        >
          <IconPlus className="h-4 w-4" />
        </button>
      </div>
      <textarea
        value={notes}
        onChange={(e) => setNotes(e.target.value)}
        rows={3}
        maxLength={520}
        placeholder="特殊注意事项，如用药禁忌、接送人约定"
        className="w-full resize-none rounded-2xl bg-white px-3.5 py-2.5 text-sm leading-relaxed text-ink ring-1 ring-navy/10 outline-none focus:ring-2 focus:ring-navy/25"
      />
      {err && <p className="text-[11px] font-medium text-rose-500">{err}</p>}
      <div className="flex gap-2">
        <button
          type="button"
          onClick={() => {
            setErr('')
            onCancel()
            push('已取消编辑', 'info')
          }}
          className="h-10 w-24 rounded-2xl bg-warmgray text-xs font-semibold text-navy/80"
        >
          取消
        </button>
        <button
          type="button"
          disabled={saving}
          onClick={() => void save()}
          className={cx(
            'h-10 flex-1 rounded-2xl bg-btn-gradient text-xs font-semibold text-white shadow-btn',
            saving ? 'opacity-60' : 'active:scale-[0.99]',
          )}
        >
          {saving ? '保存中…' : '保存健康档案'}
        </button>
      </div>
    </div>
  )
}

function EditorTags({
  title,
  items,
  onChange,
  active,
  onFocus,
}: {
  title: string
  items: string[]
  onChange: (v: string[]) => void
  active: boolean
  onFocus: () => void
}) {
  return (
    <div onClick={onFocus} className={cx('rounded-2xl p-2.5 transition', active ? 'bg-ivory ring-1 ring-gold/30' : 'bg-warmgray/60')}>
      <p className="text-[11px] font-semibold text-muted">
        {title} <span className="font-normal">（{items.length}/20）</span>
      </p>
      {items.length === 0 ? (
        <p className="mt-1 text-[11px] text-muted/80">暂无，输入后点加号添加</p>
      ) : (
        <ul className="mt-1.5 flex flex-wrap gap-1.5">
          {items.map((t) => (
            <li key={t} className="flex items-center gap-1 rounded-full bg-white px-2.5 py-1 text-[11px] text-ink ring-1 ring-navy/10">
              {t}
              <button
                type="button"
                aria-label={`删除 ${t}`}
                onClick={(e) => {
                  e.stopPropagation()
                  onChange(items.filter((x) => x !== t))
                }}
                className="text-muted hover:text-rose-500"
              >
                <IconX className="h-3 w-3" />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
