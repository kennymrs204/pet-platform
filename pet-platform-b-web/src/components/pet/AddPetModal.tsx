import { useEffect, useRef, useState, type ReactNode } from 'react'
import Modal from '../ui/Modal'
import { IconPaw, IconSearch, IconUser } from '../ui/icons'
import { useToast } from '../ui/Toast'
import { BizError } from '../../api/types'
import { CUSTOMER_STATUS_LABEL, customersApi, type CustomerRow } from '../../api/customers'
import type { PageData } from '../../api/dashboard'
import type { PetProfile } from '../../api/pets'
import {
  EMPTY_PET_DRAFT,
  buildPetCreateBody,
  canStartPetSubmit,
  createLatestOnly,
  hasCustomerSelection,
  petCreateErrorText,
  todayISO,
  validatePetDraft,
  type LatestOnlyGate,
  type PetDraft,
  type PetDraftErrors,
} from '../../lib/petManage'
import { cx } from '../../lib/cx'

const CUSTOMER_PAGE_SIZE = 10
const INPUT = 'h-10 w-full rounded-lg bg-white px-3 text-sm text-ink ring-1 placeholder:text-muted focus:outline-none'
const INPUT_OK = `${INPUT} ring-line focus:ring-blue`
const INPUT_BAD = `${INPUT} ring-[#E8506B] focus:ring-[#E8506B]`

/**
 * 新增宠物：先搜索/选择本店客户，再填写宠物资料。
 * 创建继续走 customersApi.createPet（POST /store/customers/{userId}/pets），不新增后端接口。
 */
export default function AddPetModal({
  storeId,
  onClose,
  onCreated,
}: {
  storeId: string
  onClose: () => void
  onCreated: (pet: PetProfile) => void
}) {
  const { push } = useToast()
  const [keyword, setKeyword] = useState('')
  const [debounced, setDebounced] = useState('')
  const [pageNum, setPageNum] = useState(1)
  const [listTick, setListTick] = useState(0)
  const [customers, setCustomers] = useState<PageData<CustomerRow> | null>(null)
  const [listBusy, setListBusy] = useState(true)
  const [listError, setListError] = useState('')
  const [selected, setSelected] = useState<CustomerRow | null>(null)
  const [draft, setDraft] = useState<PetDraft>(EMPTY_PET_DRAFT)
  const [errors, setErrors] = useState<PetDraftErrors>({})
  const [topError, setTopError] = useState('')
  const [busy, setBusy] = useState(false)

  const gateRef = useRef<LatestOnlyGate | null>(null)
  if (!gateRef.current) gateRef.current = createLatestOnly()
  const gate = gateRef.current
  const lastStoreRef = useRef(storeId)

  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(keyword.trim()), 300)
    return () => window.clearTimeout(timer)
  }, [keyword])

  /** 门店上下文变化时丢弃旧选择，避免把 A 店客户带到 B 店提交。 */
  useEffect(() => {
    if (lastStoreRef.current === storeId) return
    lastStoreRef.current = storeId
    setSelected(null)
    setDraft(EMPTY_PET_DRAFT)
    setErrors({})
    setTopError('')
    setKeyword('')
    setDebounced('')
    setPageNum(1)
    setCustomers(null)
    setListTick(0)
  }, [storeId])

  useEffect(() => {
    const isLatest = gate.begin()
    setListBusy(true)
    setListError('')
    customersApi
      .list({ keyword: debounced || undefined, pageNum, pageSize: CUSTOMER_PAGE_SIZE })
      .then((data) => {
        if (!isLatest()) return
        setCustomers(data)
      })
      .catch((err: unknown) => {
        if (!isLatest()) return
        setListError(err instanceof BizError ? err.message : '客户列表加载失败，请重试')
      })
      .finally(() => {
        if (isLatest()) setListBusy(false)
      })
    return () => gate.invalidate()
  }, [debounced, pageNum, listTick, gate])

  const set = <K extends keyof PetDraft>(key: K, value: PetDraft[K]) => {
    setDraft((prev) => ({ ...prev, [key]: value }))
    setErrors((prev) => ({ ...prev, [key]: undefined }))
    setTopError('')
  }

  const submit = async () => {
    if (busy) return
    if (!selected || !hasCustomerSelection(selected.userId)) {
      setTopError('请先选择客户')
      return
    }
    const next = validatePetDraft(draft)
    setErrors(next)
    if (Object.keys(next).length > 0) {
      push('还有几项需要修正', 'error')
      return
    }

    setBusy(true)
    setTopError('')
    try {
      const pet = await customersApi.createPet(selected.userId, buildPetCreateBody(draft))
      push(`已新增宠物：${pet.name}`, 'success')
      onCreated(pet)
    } catch (err) {
      const biz = err instanceof BizError ? err : null
      setTopError(petCreateErrorText(biz?.code ?? 0, biz?.message ?? ''))
    } finally {
      setBusy(false)
    }
  }

  const totalPages = customers ? Math.max(1, Math.ceil(Number(customers.total) / CUSTOMER_PAGE_SIZE)) : 1
  const rows = customers?.list ?? []

  return (
    <Modal
      open
      title="添加宠物"
      description="选择本店已有客户，为该客户新增宠物档案。第一版不支持门店代传头像，头像可由顾客在 C 端补充。"
      onClose={onClose}
      width="max-w-[720px]"
      footer={
        <>
          <button
            type="button"
            disabled={busy}
            onClick={onClose}
            className="h-10 rounded-xl bg-white px-4 text-sm font-semibold text-ink ring-1 ring-line transition hover:border-blue hover:text-blue disabled:opacity-60"
          >
            取消
          </button>
          <button
            type="button"
            disabled={!canStartPetSubmit(selected?.userId, busy)}
            onClick={() => void submit()}
            className="flex h-10 items-center gap-2 rounded-xl bg-[#1E3A5F] px-5 text-sm font-bold text-white transition hover:bg-[#16304F] disabled:cursor-not-allowed disabled:opacity-50"
          >
            {busy ? '提交中…' : '确认新增'}
          </button>
        </>
      }
    >
      <div className="max-h-[68vh] overflow-y-auto pr-1">
        <section className="rounded-xl bg-blue-mist/50 p-3.5 ring-1 ring-line">
          <div className="flex items-center justify-between gap-2">
            <p className="flex items-center gap-1.5 text-xs font-bold text-ink">
              <IconUser className="h-4 w-4 text-blue" /> 第一步：选择客户
            </p>
            {selected && (
              <button
                type="button"
                onClick={() => {
                  setSelected(null)
                  setTopError('')
                }}
                className="text-[11px] font-semibold text-blue hover:text-blue-deep"
              >
                更换客户
              </button>
            )}
          </div>

          {selected ? (
            <div className="mt-2 flex items-center gap-2 rounded-lg bg-white px-3 py-2.5 ring-1 ring-line">
              <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-blue-soft text-blue">
                <IconPaw className="h-4 w-4" />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-[13px] font-bold text-ink">{selected.nickname}</span>
                <span className="block truncate text-[11px] text-muted">
                  {selected.phoneMasked || '手机号未提供'} · 累计到店 {selected.visitCount} 次
                </span>
              </span>
              <span className="shrink-0 text-[11px] font-semibold text-emerald-600">已选择</span>
            </div>
          ) : (
            <>
              <label className="relative mt-2 block">
                <IconSearch className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
                <input
                  type="search"
                  value={keyword}
                  onChange={(e) => {
                    setKeyword(e.target.value)
                    setPageNum(1)
                  }}
                  placeholder="搜索昵称 / 手机号 / 宠物名"
                  aria-label="搜索本店客户"
                  className="h-9 w-full rounded-lg bg-white pl-9 pr-3 text-sm text-ink ring-1 ring-line placeholder:text-muted focus:outline-none focus:ring-blue"
                />
              </label>

              {listBusy && (
                <p className="py-5 text-center text-xs text-muted" role="status">
                  客户列表加载中…
                </p>
              )}
              {!listBusy && listError && (
                <div className="flex flex-col items-center gap-2 py-4">
                  <p className="text-xs text-[#E8506B]">{listError}</p>
                  <button
                    type="button"
                    onClick={() => setListTick((t) => t + 1)}
                    className="h-7 rounded-lg border border-line bg-white px-3 text-[11px] font-semibold text-ink hover:border-blue hover:text-blue"
                  >
                    重试
                  </button>
                </div>
              )}
              {!listBusy && !listError && rows.length === 0 && (
                <p className="py-5 text-center text-xs text-muted">
                  {debounced ? `没有匹配「${debounced}」的本店客户` : '本店暂无客户，暂不能新增宠物'}
                </p>
              )}
              {!listBusy && !listError && rows.length > 0 && (
                <ul className="mt-2 flex max-h-[220px] flex-col gap-1.5 overflow-y-auto">
                  {rows.map((c) => (
                    <li key={c.userId}>
                      <button
                        type="button"
                        onClick={() => {
                          setSelected(c)
                          setTopError('')
                        }}
                        className="flex w-full items-center gap-2 rounded-lg bg-white px-3 py-2 text-left ring-1 ring-line transition hover:bg-blue-soft/50 hover:ring-blue/30"
                      >
                        <span className="grid h-7 w-7 shrink-0 place-items-center rounded-md bg-blue-soft text-blue">
                          <IconUser className="h-3.5 w-3.5" />
                        </span>
                        <span className="min-w-0 flex-1">
                          <span className="block truncate text-[13px] font-semibold text-ink">{c.nickname}</span>
                          <span className="block truncate text-[11px] text-muted">
                            {c.phoneMasked || '手机号未提供'} · {CUSTOMER_STATUS_LABEL[c.status] ?? c.status}
                          </span>
                        </span>
                        <span className="shrink-0 text-[11px] text-muted">选择</span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}

              {!listBusy && !listError && totalPages > 1 && (
                <div className="mt-2 flex items-center justify-center gap-3">
                  <button
                    type="button"
                    disabled={pageNum <= 1}
                    onClick={() => setPageNum((n) => Math.max(1, n - 1))}
                    className="h-7 rounded-md border border-line bg-white px-2.5 text-[11px] font-semibold text-ink disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    上一页
                  </button>
                  <span className="text-[11px] text-muted">
                    第 {pageNum} / {totalPages} 页
                  </span>
                  <button
                    type="button"
                    disabled={pageNum >= totalPages}
                    onClick={() => setPageNum((n) => Math.min(totalPages, n + 1))}
                    className="h-7 rounded-md border border-line bg-white px-2.5 text-[11px] font-semibold text-ink disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    下一页
                  </button>
                </div>
              )}
            </>
          )}
        </section>

        {selected && (
          <section className="mt-4">
            <p className="mb-3 flex items-center gap-1.5 text-xs font-bold text-ink">
              <IconPaw className="h-4 w-4 text-blue" /> 第二步：填写宠物资料
            </p>
            <div className="flex flex-col gap-3.5">
              <Field label="宠物昵称" required error={errors.name}>
                <input
                  value={draft.name}
                  onChange={(e) => set('name', e.target.value)}
                  maxLength={50}
                  placeholder="如：豆包"
                  aria-label="宠物昵称"
                  aria-invalid={!!errors.name}
                  className={errors.name ? INPUT_BAD : INPUT_OK}
                />
              </Field>

              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <Field label="物种" required error={errors.species}>
                  <select
                    value={draft.species}
                    onChange={(e) => set('species', e.target.value as PetDraft['species'])}
                    aria-label="物种"
                    className={errors.species ? INPUT_BAD : INPUT_OK}
                  >
                    <option value="DOG">犬</option>
                    <option value="CAT">猫</option>
                    <option value="OTHER">其他</option>
                  </select>
                </Field>
                <Field label="性别" error={errors.gender}>
                  <select
                    value={draft.gender}
                    onChange={(e) => set('gender', e.target.value as PetDraft['gender'])}
                    aria-label="性别"
                    className={errors.gender ? INPUT_BAD : INPUT_OK}
                  >
                    <option value="UNKNOWN">未知</option>
                    <option value="MALE">公</option>
                    <option value="FEMALE">母</option>
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
                <Field label="生日" error={errors.birthday}>
                  <input
                    type="date"
                    value={draft.birthday}
                    max={todayISO()}
                    onChange={(e) => set('birthday', e.target.value)}
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

              <Field label="备注（≤500 字）" error={errors.remark}>
                <textarea
                  value={draft.remark}
                  onChange={(e) => set('remark', e.target.value)}
                  maxLength={500}
                  rows={3}
                  placeholder="如：对鸡肉过敏"
                  aria-label="宠物备注"
                  aria-invalid={!!errors.remark}
                  className={cx(errors.remark ? INPUT_BAD : INPUT_OK, 'h-20 resize-none py-2 leading-relaxed')}
                />
              </Field>

              <p className="rounded-lg bg-blue-mist/70 px-3 py-2 text-[11px] leading-relaxed text-muted">
                体型由服务端按体重自动计算；宠物头像可由顾客在 C 端档案中补充。
              </p>
              {topError && <p className="text-[11px] text-[#E8506B]">{topError}</p>}
            </div>
          </section>
        )}

        {!selected && topError && <p className="mt-3 text-[11px] text-[#E8506B]">{topError}</p>}
      </div>
    </Modal>
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
