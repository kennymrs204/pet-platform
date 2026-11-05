import { useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import Layout from '../components/layout/Layout'
import Modal from '../components/ui/Modal'
import BookAppointmentModal from '../components/appointment/BookAppointmentModal'
import {
  IconBox,
  IconCalendar,
  IconCat,
  IconChevronDown,
  IconChevronRight,
  IconClock,
  IconDog,
  IconEdit,
  IconHelp,
  IconMoon,
  IconMsg,
  IconPaw,
  IconPlus,
  IconSearch,
  IconTrendUp,
  IconUsers,
  IconWallet,
} from '../components/ui/icons'
import { useToast } from '../components/ui/Toast'
import { storeContext } from '../auth/storeContext'
import { chatApi } from '../api/chat'
import { BizCode, BizError } from '../api/types'
import { fenToYuanText } from '../api/dashboard'
import {
  CUSTOMER_STATUS_LABEL,
  CUSTOMER_TAB_LABEL,
  VISIT_STATUS_LABEL,
  customersApi,
  customerInitial,
  daysSince,
  type CustomerOrderRow,
  type CustomerPetRef,
  type CustomerRow,
  type CustomerStats,
  type CustomerTab,
  type CustomerVisit,
  /** 360 详情 DTO——与本地组件 CustomerDetail 同名，导入时取别名 */
  type CustomerDetail as CustomerDetailData,
} from '../api/customers'
import {
  PET_STATUS_LABEL,
  SPECIES_LABEL,
  petAgeText,
  petWeightText,
  petsApi,
  type PetBoardingBrief,
  type PetHealthDetail,
  type PetHealthSummary,
  type PetLastService,
} from '../api/pets'
import {
  ORDER_STATUS_LABEL,
  type OrderStatus,
} from '../api/orders'
import { cx } from '../lib/cx'
import { TONE } from '../lib/tone'
import type { IconComp, Tone } from '../mock/dashboard'
import { FAQS } from '../mock/customers'

const NAVY = 'bg-[#1E3A5F] text-white hover:bg-[#16304F]'
const TABS: CustomerTab[] = ['ALL', 'ACTIVE', 'DORMANT', 'IN_BOARDING']
const PAGE_SIZE = 20

/** 四个分类的图标与口径提示（hint 与后端 matchesTab 的口径逐条对齐，不自行发明） */
const TAB_META: Record<CustomerTab, { Icon: IconComp; hint: string }> = {
  ALL: { Icon: IconUsers, hint: '在本店有过预约的顾客（已排除员工账号）' },
  ACTIVE: { Icon: IconTrendUp, hint: '近 30 天到过店，或当前有有效预约' },
  DORMANT: { Icon: IconMoon, hint: '超过 30 天未到店，且当前没有有效预约' },
  IN_BOARDING: { Icon: IconBox, hint: '名下宠物正在本店托管（含每日护理）' },
}

/**
 * 客户管理页（ADR-017 真实契约 + ADR-057 代客建档 + ADR-058 编辑资料/代建宠物）。
 * 列表 / 统计 / 客户 360 直连后端，无演示兜底：拿不到数据就显示错误并给出原因，
 * 因为假数据会一起掩盖权限未授予、门店未切换、后端未部署这三类真实故障。
 * 写侧三个动作：代客建档、编辑资料（含备注）、代建宠物（均居中弹窗）；托管史仍无契约 → 诚实占位（订单史已接入，打通轮 P4）。
 * 分类走 URL（?tab=，与设置页同族）：可深链、可刷新恢复，换类回第 1 页并收起展开行。
 */
export default function CustomersPage() {
  const { push } = useToast()
  const notReady = (label: string) => () => push(`${label}：功能暂未接入`, 'info')
  const ctx = useSyncExternalStore(storeContext.subscribe, storeContext.get)
  const storeId = ctx.storeId

  const [searchParams, setSearchParams] = useSearchParams()
  const rawTab = searchParams.get('tab')
  const tab: CustomerTab = (TABS as string[]).includes(rawTab ?? '') ? (rawTab as CustomerTab) : 'ALL'
  const [keyword, setKeyword] = useState('')
  const kw = useDebounced(keyword, 300)
  const [pageNum, setPageNum] = useState(1)
  const [openId, setOpenId] = useState<string | null>(null)
  /** 新增客户弹窗（居中 Modal；字段状态在弹窗子树内，关闭即随卸载清空） */
  const [createOpen, setCreateOpen] = useState(false)
  /** 编辑资料弹窗的目标客户；null=关闭 */
  const [editId, setEditId] = useState<string | null>(null)
  /** 改档/代建宠物成功后的刷新刻度：打开中的 360 与列表、统计一起重拉 */
  const [refreshTick, setRefreshTick] = useState(0)

  // 查询条件一变就回到第 1 页：否则在「沉睡客户」第 3 页上切分类会落到空列表
  useEffect(() => {
    setPageNum(1)
  }, [tab, kw, storeId])

  /** 换分类 = 写 URL（replace，避免把后台历史塞满）；同分类重复点不做任何事 */
  const selectTab = (key: string) => {
    if (key === tab) return
    setOpenId(null)
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev)
        if (key === 'ALL') next.delete('tab')
        else next.set('tab', key)
        return next
      },
      { replace: true },
    )
  }

  const stats = useAsync(() => customersApi.stats(), storeId)
  const list = useAsync(
    () => customersApi.list({ tab, keyword: kw || undefined, pageNum, pageSize: PAGE_SIZE }),
    `${storeId}|${tab}|${kw}|${pageNum}`,
  )

  const page = list.block.state === 'ready' ? list.block.data : null
  const rows = page?.list ?? []
  const totalPages = page ? Math.ceil(Number(page.total) / PAGE_SIZE) : 1

  /** 建档成功后把分类切回 ALL（同一个 URL 写口，不绕过 ?tab= 单一来源） */
  const setTabAll = () => {
    if (tab === 'ALL') return
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev)
        next.delete('tab')
        return next
      },
      { replace: true },
    )
  }

  /** 建档成功后：关弹窗 → 切回全部客户 → 用完整手机号定位（uk_phone 唯一 ⇒ 列表恰一行）→ 刷统计 */
  const onCreated = (phone: string) => {
    setCreateOpen(false)
    setTabAll()
    setKeyword(phone)
    stats.reload()
  }

  return (
    <Layout active="customers">
      <div className="flex flex-col gap-4 p-4 sm:p-6">
        <div className="rounded-2xl bg-white px-4 py-3.5 shadow-soft ring-1 ring-line sm:px-5">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="min-w-0">
              <h1 className="text-[22px] font-black leading-tight text-ink">客户管理</h1>
              <p className="mt-0.5 text-xs text-muted">本店顾客的档案、归属宠物与到店记录</p>
            </div>
            <button
              type="button"
              onClick={() => setCreateOpen(true)}
              className={cx('flex h-10 items-center gap-1.5 rounded-xl px-4 text-sm font-bold transition', NAVY)}
            >
              <IconPlus className="h-4 w-4" /> 新增客户
            </button>
          </div>
        </div>

        {/* 关即卸载：表单状态在弹窗子树里，随关闭自然清空，不残留上一次输入 */}
        {createOpen && <CreateCustomerModal onClose={() => setCreateOpen(false)} onCreated={onCreated} />}
        {editId && (
          <EditCustomerModal
            userId={editId}
            onClose={() => setEditId(null)}
            onDataChanged={() => {
              setRefreshTick((t) => t + 1)
              list.reload()
              stats.reload()
            }}
          />
        )}

        <div className="grid gap-4 2xl:grid-cols-[minmax(0,1fr)_300px]">
          <div className="flex min-w-0 flex-col gap-4">
            <div className="rounded-2xl bg-white p-4 shadow-soft ring-1 ring-line sm:p-5">
              {/* 工具栏：分类标签（左）+ 搜索（右）；空间不足时自适应换行 */}
              <div className="flex flex-wrap items-center gap-2.5">
                <div role="tablist" aria-label="客户分类" className="flex flex-wrap gap-2">
                  {TABS.map((key) => (
                    <button
                      key={key}
                      type="button"
                      role="tab"
                      aria-selected={tab === key}
                      onClick={() => selectTab(key)}
                      className={cx(
                        'h-9 rounded-full px-4 text-sm font-semibold transition',
                        tab === key ? 'bg-blue text-white' : 'bg-white text-ink ring-1 ring-line hover:text-blue',
                      )}
                    >
                      {CUSTOMER_TAB_LABEL[key]}
                    </button>
                  ))}
                </div>
                <label className="relative w-full sm:ml-auto sm:w-[260px]">
                  <IconSearch className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
                  <input
                    type="search"
                    value={keyword}
                    onChange={(e) => {
                      setKeyword(e.target.value)
                      setOpenId(null)
                    }}
                    placeholder="搜昵称 / 手机号 / 宠物名"
                    aria-label="搜索客户"
                    className="h-9 w-full rounded-full bg-white pl-9 pr-3 text-sm text-ink ring-1 ring-line placeholder:text-muted focus:outline-none focus:ring-blue"
                  />
                </label>
              </div>
              <p className="mt-2 text-[11px] leading-relaxed text-muted">{TAB_META[tab].hint}</p>

              <div className="mt-3 flex flex-col gap-2.5">
                {list.block.state === 'loading' && <ListLoading />}
                {list.block.state === 'error' && <ListError message={list.block.message} onRetry={list.reload} />}
                {page && page.list.length === 0 && (
                  <p className="rounded-2xl bg-blue-mist/60 py-12 text-center text-sm text-muted">
                    {kw ? `没有匹配「${kw}」的客户` : '当前门店暂无客户（有过预约或本店建档的顾客会出现在这里）'}
                  </p>
                )}
                {rows.map((c) => (
                  <CustomerRowBlock
                    key={c.userId}
                    customer={c}
                    open={openId === c.userId}
                    tick={refreshTick}
                    onToggle={() => setOpenId((v) => (v === c.userId ? null : c.userId))}
                    onNotReady={notReady}
                    onEdit={setEditId}
                  />
                ))}
                {page && totalPages > 1 && (
                  <div className="mt-1 flex items-center justify-center gap-3">
                    <PagerButton disabled={pageNum <= 1} onClick={() => setPageNum((n) => n - 1)} dir="prev" />
                    <p className="text-[11px] text-muted">
                      共 {page.total} 位客户 · 第 {pageNum} / {totalPages} 页
                    </p>
                    <PagerButton disabled={pageNum >= totalPages} onClick={() => setPageNum((n) => n + 1)} dir="next" />
                  </div>
                )}
              </div>
            </div>
          </div>

          <div className="flex min-w-0 flex-col gap-4">
            <StatsCard block={stats.block} onRetry={stats.reload} />
            <FaqCard />
          </div>
        </div>
      </div>
    </Layout>
  )
}

/* ---- 新增客户弹窗（P0）：字段状态在子树内，关闭即卸载清空；字段级错误就近显示 ---- */

const PHONE_RE = /^1[3-9]\d{9}$/
const INPUT = 'h-10 w-full rounded-lg bg-white px-3 text-sm text-ink ring-1 placeholder:text-muted focus:outline-none'
const INPUT_OK = `${INPUT} ring-line focus:ring-blue`
const INPUT_BAD = `${INPUT} ring-[#E8506B] focus:ring-[#E8506B]`

function CreateCustomerModal({ onClose, onCreated }: { onClose: () => void; onCreated: (phone: string) => void }) {
  const { push } = useToast()
  const [phone, setPhone] = useState('')
  const [nickname, setNickname] = useState('')
  const [busy, setBusy] = useState(false)
  const [errs, setErrs] = useState<{ phone?: string; nickname?: string; top?: string }>({})

  const submit = async () => {
    const p = phone.trim()
    const n = nickname.trim()
    const next: typeof errs = {}
    if (!p) next.phone = '请输入手机号'
    else if (!PHONE_RE.test(p)) next.phone = '手机号格式不正确（须为 13–19 开头的 11 位号码）'
    if (!n) next.nickname = '请输入客户姓名'
    if (next.phone || next.nickname) {
      setErrs(next)
      return
    }
    setBusy(true)
    setErrs({})
    try {
      const row = await customersApi.create({ phone: p, nickname: n })
      push(`已新增客户：${row.nickname}（${row.phoneMasked}）`, 'success')
      onCreated(p)
    } catch (err) {
      const code = err instanceof BizError ? err.code : 0
      // 20001 能定位到字段 → 就近显示；其余错误给区块级提示
      if (code === BizCode.PHONE_REGISTERED) setErrs({ phone: '该手机号已在平台注册或建档' })
      else setErrs({ top: err instanceof BizError ? err.message : '新增失败，请稍后重试' })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      open
      title="新增客户"
      description="为尚未注册的客户创建档案。"
      onClose={onClose}
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
            disabled={busy}
            onClick={() => void submit()}
            className={cx('flex h-10 items-center gap-2 rounded-xl px-5 text-sm font-bold transition disabled:opacity-60', NAVY)}
          >
            {busy && <Spinner />}
            {busy ? '提交中…' : '确认新增'}
          </button>
        </>
      }
    >
      <div className="flex flex-col gap-3.5">
        <label className="flex flex-col gap-1.5">
          <span className="text-xs font-semibold text-ink">
            手机号 <span className="text-[#E8506B]">*</span>
          </span>
          <input
            className={errs.phone ? INPUT_BAD : INPUT_OK}
            value={phone}
            onChange={(e) => {
              // 只收数字并截断 11 位：粘贴 139-0000-8801 / 139 0000 8801 这类带分隔符的也能用
              setPhone(e.target.value.replace(/\D/g, '').slice(0, 11))
              if (errs.phone) setErrs((s) => ({ ...s, phone: undefined }))
            }}
            inputMode="numeric"
            placeholder="请输入 11 位手机号（13–19 开头）"
            aria-label="新客户手机号"
            aria-invalid={!!errs.phone}
          />
          {errs.phone && <span className="text-[11px] text-[#E8506B]">{errs.phone}</span>}
        </label>
        <label className="flex flex-col gap-1.5">
          <span className="text-xs font-semibold text-ink">
            客户姓名 <span className="text-[#E8506B]">*</span>
          </span>
          <input
            className={errs.nickname ? INPUT_BAD : INPUT_OK}
            value={nickname}
            onChange={(e) => {
              setNickname(e.target.value)
              if (errs.nickname) setErrs((s) => ({ ...s, nickname: undefined }))
            }}
            maxLength={50}
            placeholder="请输入客户姓名"
            aria-label="新客户姓名"
            aria-invalid={!!errs.nickname}
          />
          {errs.nickname && <span className="text-[11px] text-[#E8506B]">{errs.nickname}</span>}
        </label>
        <p className="rounded-lg bg-blue-mist/70 px-3 py-2 text-[11px] leading-relaxed text-muted">
          仅支持为平台尚未注册的手机号建档。客户后续可通过验证码登录，或通过「忘记密码」设置密码。
        </p>
        {errs.top && <p className="text-[11px] text-[#E8506B]">{errs.top}</p>}
      </div>
    </Modal>
  )
}

/* ---- 编辑资料弹窗（ADR-058）：双视图（资料 ⇄ 新增宠物），关即卸载；字段级错误就近显示 ---- */

function EditCustomerModal({ userId, onClose, onDataChanged }: { userId: string; onClose: () => void; onDataChanged: () => void }) {
  const { block, reload } = useAsync(() => customersApi.detail(userId), userId)
  const [view, setView] = useState<'profile' | 'pet'>('profile')

  if (block.state === 'loading') {
    return (
      <Modal open title="编辑资料" onClose={onClose}>
        <p className="py-8 text-center text-xs text-muted">
          <Spinner /> 加载客户资料…
        </p>
      </Modal>
    )
  }
  if (block.state === 'error') {
    return (
      <Modal open title="编辑资料" onClose={onClose}>
        <div className="flex flex-col items-center gap-2 py-6">
          <p className="text-xs text-[#E8506B]">资料加载失败：{block.message}</p>
          <button type="button" onClick={reload} className="h-7 rounded-lg border border-line px-3 text-[11px] font-semibold text-ink hover:border-blue hover:text-blue">
            重试
          </button>
        </div>
      </Modal>
    )
  }
  const d = block.data
  if (view === 'pet') {
    return (
      <PetForm
        userId={userId}
        onBack={() => setView('profile')}
        onCreated={() => {
          reload()
          onDataChanged()
          setView('profile')
        }}
      />
    )
  }
  return (
    <ProfileForm
      detail={d}
      onClose={onClose}
      onSaved={() => {
        onDataChanged()
        onClose()
      }}
      onAddPet={() => setView('pet')}
    />
  )
}

/** 视图一：客户资料（姓名 / 默认联系电话 / 客户备注）+ 名下宠物列表与代建入口 */
function ProfileForm({ detail: d, onClose, onSaved, onAddPet }: { detail: CustomerDetailData; onClose: () => void; onSaved: () => void; onAddPet: () => void }) {
  const { push } = useToast()
  const [nickname, setNickname] = useState(d.nickname)
  const [contact, setContact] = useState(d.profile.defaultContactPhone ?? '')
  const [remark, setRemark] = useState(d.profile.remark ?? '')
  const [busy, setBusy] = useState(false)
  const [errs, setErrs] = useState<{ nickname?: string; contact?: string; remark?: string; top?: string }>({})

  const submit = async () => {
    const n = nickname.trim()
    const c = contact.trim()
    const r = remark.trim()
    const next: typeof errs = {}
    if (!n) next.nickname = '请输入客户姓名'
    else if (n.length > 50) next.nickname = '客户姓名不超过 50 字'
    if (c && !PHONE_RE.test(c)) next.contact = '默认联系电话须为 13–19 开头的手机号或留空'
    if (r.length > 500) next.remark = '客户备注不超过 500 字'
    if (next.nickname || next.contact || next.remark) {
      setErrs(next)
      return
    }
    setBusy(true)
    setErrs({})
    try {
      await customersApi.update(d.userId, { nickname: n, defaultContactPhone: c || null, remark: r || null })
      push('客户资料已保存', 'success')
      onSaved()
    } catch (err) {
      const code = err instanceof BizError ? err.code : 0
      setErrs({ top: code === BizCode.FORBIDDEN ? '当前账号无编辑权限（customer:update）' : err instanceof BizError ? err.message : '保存失败，请稍后重试' })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      open
      title="编辑资料"
      description="修改客户姓名、默认联系电话与客户备注。"
      onClose={onClose}
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
            disabled={busy}
            onClick={() => void submit()}
            className={cx('flex h-10 items-center gap-2 rounded-xl px-5 text-sm font-bold transition disabled:opacity-60', NAVY)}
          >
            {busy && <Spinner />}
            {busy ? '保存中…' : '保存资料'}
          </button>
        </>
      }
    >
      <div className="flex flex-col gap-3.5">
        <div className="flex items-center justify-between rounded-lg bg-blue-mist/70 px-3 py-2">
          <span className="text-xs font-semibold text-ink">手机号（登录凭证）</span>
          <span className="font-mono text-xs text-muted">{d.phoneMasked} · 不可修改</span>
        </div>
        <label className="flex flex-col gap-1.5">
          <span className="text-xs font-semibold text-ink">
            客户姓名 <span className="text-[#E8506B]">*</span>
          </span>
          <input
            className={errs.nickname ? INPUT_BAD : INPUT_OK}
            value={nickname}
            onChange={(e) => {
              setNickname(e.target.value)
              if (errs.nickname) setErrs((s) => ({ ...s, nickname: undefined }))
            }}
            maxLength={50}
            placeholder="请输入客户姓名"
            aria-label="客户姓名"
            aria-invalid={!!errs.nickname}
          />
          {errs.nickname && <span className="text-[11px] text-[#E8506B]">{errs.nickname}</span>}
        </label>
        <label className="flex flex-col gap-1.5">
          <span className="text-xs font-semibold text-ink">默认联系电话</span>
          <input
            className={errs.contact ? INPUT_BAD : INPUT_OK}
            value={contact}
            onChange={(e) => {
              // 与建档同款容错：只收数字、截断 11 位（粘贴带分隔符也能用）
              setContact(e.target.value.replace(/\D/g, '').slice(0, 11))
              if (errs.contact) setErrs((s) => ({ ...s, contact: undefined }))
            }}
            inputMode="numeric"
            placeholder="13–19 开头的手机号，可留空"
            aria-label="默认联系电话"
            aria-invalid={!!errs.contact}
          />
          {errs.contact && <span className="text-[11px] text-[#E8506B]">{errs.contact}</span>}
        </label>
        <label className="flex flex-col gap-1.5">
          <span className="text-xs font-semibold text-ink">客户备注（仅门店可见，≤500 字）</span>
          <textarea
            className={cx(errs.remark ? INPUT_BAD : INPUT_OK, 'h-20 py-2 leading-relaxed')}
            value={remark}
            onChange={(e) => {
              setRemark(e.target.value)
              if (errs.remark) setErrs((s) => ({ ...s, remark: undefined }))
            }}
            maxLength={500}
            rows={3}
            placeholder="如：怕生，到店先电话联系"
            aria-label="客户备注"
            aria-invalid={!!errs.remark}
          />
          {errs.remark && <span className="text-[11px] text-[#E8506B]">{errs.remark}</span>}
        </label>

        <div className="border-t border-line pt-3">
          <div className="flex items-center justify-between gap-2">
            <span className="text-xs font-semibold text-ink">名下宠物（{d.pets.length}）</span>
            <button
              type="button"
              onClick={onAddPet}
              className="flex h-8 items-center gap-1 rounded-lg bg-white px-3 text-[11px] font-semibold text-ink ring-1 ring-line transition hover:border-blue hover:text-blue"
            >
              <IconPlus className="h-3.5 w-3.5" /> 新增宠物
            </button>
          </div>
          {d.pets.length === 0 ? (
            <p className="mt-2 text-[11px] text-muted">该客户名下暂无宠物档案</p>
          ) : (
            <ul className="mt-2 flex flex-col gap-1.5">
              {d.pets.map((p) => (
                <li key={p.petId} className="flex items-center gap-2 rounded-lg bg-blue-mist/60 px-3 py-1.5 ring-1 ring-line">
                  <span className="grid h-6 w-6 shrink-0 place-items-center rounded-md bg-white text-blue">
                    {p.species === 'DOG' ? <IconDog className="h-3.5 w-3.5" /> : p.species === 'CAT' ? <IconCat className="h-3.5 w-3.5" /> : <IconPaw className="h-3.5 w-3.5" />}
                  </span>
                  <span className="truncate text-[13px] font-semibold text-ink">{p.name}</span>
                  <span className="truncate text-[11px] text-muted">{p.breed ?? '品种未登记'}</span>
                </li>
              ))}
            </ul>
          )}
        </div>

        {errs.top && <p className="text-[11px] text-[#E8506B]">{errs.top}</p>}
      </div>
    </Modal>
  )
}

/** 视图二：新增宠物（字段与校验照抄 C 端 PetCreateRequest；头像需顾客在 C 端补传） */
function PetForm({ userId, onBack, onCreated }: { userId: string; onBack: () => void; onCreated: () => void }) {
  const { push } = useToast()
  const [name, setName] = useState('')
  const [species, setSpecies] = useState('DOG')
  const [breed, setBreed] = useState('')
  const [gender, setGender] = useState('UNKNOWN')
  const [birthday, setBirthday] = useState('')
  const [weight, setWeight] = useState('')
  const [remark, setRemark] = useState('')
  const [busy, setBusy] = useState(false)
  const [errs, setErrs] = useState<{ name?: string; weight?: string; remark?: string; top?: string }>({})

  const submit = async () => {
    const n = name.trim()
    const w = weight.trim()
    const r = remark.trim()
    const next: typeof errs = {}
    if (!n) next.name = '请输入宠物昵称'
    else if (n.length > 50) next.name = '宠物昵称不超过 50 字'
    if (w) {
      const num = Number(w)
      if (!Number.isFinite(num) || num <= 0 || num > 999.99) next.weight = '体重须在 0~999.99 kg 之间'
    }
    if (r.length > 500) next.remark = '备注不超过 500 字'
    if (next.name || next.weight || next.remark) {
      setErrs(next)
      return
    }
    setBusy(true)
    setErrs({})
    try {
      await customersApi.createPet(userId, {
        name: n,
        species,
        breed: breed.trim() || null,
        gender,
        birthday: birthday || null,
        weightKg: w ? Number(w) : null,
        remark: r || null,
      })
      push(`已新增宠物：${n}`, 'success')
      onCreated()
    } catch (err) {
      const code = err instanceof BizError ? err.code : 0
      setErrs({ top: code === BizCode.FORBIDDEN ? '当前账号无编辑权限（customer:update）' : err instanceof BizError ? err.message : '新增失败，请稍后重试' })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      open
      title="新增宠物"
      description="为该客户建档一只宠物；尺寸按体重自动计算，头像可由顾客在 C 端补传。"
      onClose={onBack}
      footer={
        <>
          <button
            type="button"
            disabled={busy}
            onClick={onBack}
            className="h-10 rounded-xl bg-white px-4 text-sm font-semibold text-ink ring-1 ring-line transition hover:border-blue hover:text-blue disabled:opacity-60"
          >
            返回
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={() => void submit()}
            className={cx('flex h-10 items-center gap-2 rounded-xl px-5 text-sm font-bold transition disabled:opacity-60', NAVY)}
          >
            {busy && <Spinner />}
            {busy ? '提交中…' : '确认新增'}
          </button>
        </>
      }
    >
      <div className="flex flex-col gap-3.5">
        <label className="flex flex-col gap-1.5">
          <span className="text-xs font-semibold text-ink">
            宠物昵称 <span className="text-[#E8506B]">*</span>
          </span>
          <input
            className={errs.name ? INPUT_BAD : INPUT_OK}
            value={name}
            onChange={(e) => {
              setName(e.target.value)
              if (errs.name) setErrs((s) => ({ ...s, name: undefined }))
            }}
            maxLength={50}
            placeholder="请输入宠物昵称"
            aria-label="宠物昵称"
            aria-invalid={!!errs.name}
          />
          {errs.name && <span className="text-[11px] text-[#E8506B]">{errs.name}</span>}
        </label>
        <div className="grid grid-cols-2 gap-3">
          <label className="flex flex-col gap-1.5">
            <span className="text-xs font-semibold text-ink">物种</span>
            <select className={INPUT_OK} value={species} onChange={(e) => setSpecies(e.target.value)} aria-label="物种">
              <option value="DOG">犬</option>
              <option value="CAT">猫</option>
              <option value="OTHER">其他</option>
            </select>
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="text-xs font-semibold text-ink">性别</span>
            <select className={INPUT_OK} value={gender} onChange={(e) => setGender(e.target.value)} aria-label="性别">
              <option value="UNKNOWN">未知</option>
              <option value="MALE">公</option>
              <option value="FEMALE">母</option>
            </select>
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="text-xs font-semibold text-ink">品种</span>
            <input className={INPUT_OK} value={breed} onChange={(e) => setBreed(e.target.value)} maxLength={50} placeholder="如：柴犬" aria-label="品种" />
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="text-xs font-semibold text-ink">生日</span>
            <input type="date" className={INPUT_OK} value={birthday} onChange={(e) => setBirthday(e.target.value)} aria-label="生日" />
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="text-xs font-semibold text-ink">体重（kg）</span>
            <input
              className={errs.weight ? INPUT_BAD : INPUT_OK}
              value={weight}
              onChange={(e) => {
                setWeight(e.target.value)
                if (errs.weight) setErrs((s) => ({ ...s, weight: undefined }))
              }}
              inputMode="decimal"
              placeholder="如：5.5"
              aria-label="体重"
              aria-invalid={!!errs.weight}
            />
            {errs.weight && <span className="text-[11px] text-[#E8506B]">{errs.weight}</span>}
          </label>
        </div>
        <label className="flex flex-col gap-1.5">
          <span className="text-xs font-semibold text-ink">备注（≤500 字）</span>
          <textarea
            className={cx(errs.remark ? INPUT_BAD : INPUT_OK, 'h-16 py-2 leading-relaxed')}
            value={remark}
            onChange={(e) => {
              setRemark(e.target.value)
              if (errs.remark) setErrs((s) => ({ ...s, remark: undefined }))
            }}
            maxLength={500}
            rows={2}
            placeholder="如：对鸡肉过敏"
            aria-label="宠物备注"
            aria-invalid={!!errs.remark}
          />
          {errs.remark && <span className="text-[11px] text-[#E8506B]">{errs.remark}</span>}
        </label>
        {errs.top && <p className="text-[11px] text-[#E8506B]">{errs.top}</p>}
      </div>
    </Modal>
  )
}

/* ---- 区块数据（与首页/宠物页同模式：判别联合 + seq 丢弃陈旧响应 + 切店重拉） ---- */

/** code 保留给"范围外(20101)/无权限(10003)"这类需要给不同说法的错误，与设置页 BlockShell 同口径 */
type Block<T> = { state: 'loading' } | { state: 'error'; message: string; code: number } | { state: 'ready'; data: T }

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
        const biz = err instanceof BizError ? err : null
        setBlock({ state: 'error', message: biz?.message ?? '加载失败，请重试', code: biz?.code ?? -1 })
      },
    )
  }, [reloadKey, tick])

  return { block, reload: () => setTick((t) => t + 1) }
}

function useDebounced(value: string, ms: number): string {
  const [out, setOut] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setOut(value), ms)
    return () => clearTimeout(t)
  }, [value, ms])
  return out
}

/* ---- 客户行 + 行内展开（沿用托管页「展开明细」范式） ---- */

function CustomerRowBlock({
  customer: c,
  open,
  tick,
  onToggle,
  onNotReady,
  onEdit,
}: {
  customer: CustomerRow
  open: boolean
  /** 改档/代建宠物后的刷新刻度：并入 360 的取数键，开着的那条也跟着重拉 */
  tick: number
  onToggle: () => void
  onNotReady: (label: string) => () => void
  onEdit: (userId: string) => void
}) {
  const days = daysSince(c.lastVisitDate)
  // lastVisitDate 可能是未来日（演示数据里含已排到的预约）→ 负数不能直接拼「N 天前」
  const lastVisitNote = days === null ? null : days < 0 ? `${-days} 天后` : days === 0 ? '今天' : `${days} 天前`
  return (
    <div className="overflow-hidden rounded-2xl bg-blue-mist/60 ring-1 ring-line">
      <button type="button" aria-expanded={open} onClick={onToggle} className="flex w-full items-center gap-3 p-3 text-left transition hover:bg-white/60">
        <Avatar name={c.nickname} id={c.userId} url={c.avatarUrl} />
        <div className="min-w-0 flex-1">
          <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[15px] font-black text-ink">
            <span className="truncate">{c.nickname}</span>
            <span className="font-mono text-[12px] font-semibold text-muted">{c.phoneMasked}</span>
            <Badge label={CUSTOMER_STATUS_LABEL[c.status] ?? c.status} tone={c.status === 'NORMAL' ? 'green' : 'pink'} />
            {BigInt(c.inStoreStayCount) > 0n && <Badge label={`在店托管 ${c.inStoreStayCount}`} tone="orange" />}
            {isNew(c) && <Badge label="新客" tone="blue" />}
            {!c.hasValidAppointment && <Badge label="无有效预约" tone="gray" />}
          </p>
          <p className="mt-0.5 text-xs text-muted">
            首次到店 {c.firstVisitDate ?? '—'} · 最近 {c.lastVisitDate ?? '—'}
            {lastVisitNote && `（${lastVisitNote}）`} · 累计到店 {c.visitCount} 次
          </p>
        </div>
        <div className="hidden shrink-0 text-right sm:block">
          <p className="text-[15px] font-black text-ink">{c.totalPaidAmount === null ? '—' : fenToYuanText(c.totalPaidAmount)}</p>
          <p className="text-[11px] text-muted">累计消费</p>
        </div>
        <IconChevronDown className={cx('h-4 w-4 shrink-0 text-muted transition-transform', open && 'rotate-180')} />
      </button>
      {open && <CustomerDetail userId={c.userId} tick={tick} onNotReady={onNotReady} onEdit={onEdit} />}
    </div>
  )
}

function isNew(c: CustomerRow): boolean {
  const d = daysSince(c.firstVisitDate)
  return d !== null && d <= 30 && BigInt(c.visitCount) <= 1n
}

function CustomerDetail({ userId, tick, onNotReady, onEdit }: { userId: string; tick: number; onNotReady: (label: string) => () => void; onEdit: (userId: string) => void }) {
  const navigate = useNavigate()
  const { push } = useToast()
  const { block, reload } = useAsync(() => customersApi.detail(userId), `${userId}|${tick}`)
  const [bookOpen, setBookOpen] = useState(false)
  const [chatBusy, setChatBusy] = useState(false)

  /**
   * 「发送消息」→ 客户沟通工作台（P4）：customerUserId 来自 360 的真实 userId（本组件使用的就是它），
   * 门店归属由服务端按 X-Store-Id 裁决（前端不传 storeId）；已有会话复用（后端 get-or-create），
   * 跳到工作台 ?open={conversationId} 由工作台经详情接口再校验并加载。失败如实提示，不伪造会话。
   */
  const openChat = async () => {
    if (chatBusy) return
    setChatBusy(true)
    try {
      const res = await chatApi.open({ customerUserId: userId })
      navigate(`/customer-chat?open=${res.conversation.id}`)
    } catch (e) {
      push(e instanceof BizError ? e.message : '打开会话失败，请稍后重试', 'error')
    } finally {
      setChatBusy(false)
    }
  }

  if (block.state === 'loading') {
    return (
      <p className="border-t border-line bg-white px-4 py-6 text-center text-xs text-muted">
        <Spinner /> 加载客户 360…
      </p>
    )
  }
  if (block.state === 'error') {
    return (
      <div className="flex flex-col items-center gap-2 border-t border-line bg-white px-4 py-6">
        <p className="text-xs text-[#E8506B]">客户明细加载失败：{block.message}</p>
        <button type="button" onClick={reload} className="h-7 rounded-lg border border-line px-3 text-[11px] font-semibold text-ink hover:border-blue hover:text-blue">
          重试
        </button>
      </div>
    )
  }
  const d = block.data
  return (
    <div className="flex flex-col gap-4 border-t border-line bg-white p-4">
      <div className="grid gap-x-6 gap-y-2.5 sm:grid-cols-2 xl:grid-cols-3">
        <Field label="注册时间" value={d.profile.registeredAt ? `${d.profile.registeredAt.slice(0, 10)} ${d.profile.registeredAt.slice(11, 16)}` : '—'} />
        <Field label="默认联系电话" value={d.profile.defaultContactPhone ?? '未填写'} muted={!d.profile.defaultContactPhone} />
        <Field label="偏好门店" value={d.profile.preferredStoreName ?? '该客户未设置'} muted={!d.profile.preferredStoreName} />
        <Field label="营销订阅" value={d.profile.marketingOptIn ? '已订阅' : '未订阅'} />
        <Field label="账号状态" value={CUSTOMER_STATUS_LABEL[d.status] ?? d.status} />
        <Field label="客户备注" value={d.profile.remark ?? '未填写'} muted={!d.profile.remark} />
        <Field label="联系记录" value="功能暂未接入" muted />
      </div>

      <section>
        <p className="mb-2 flex items-center gap-1.5 text-xs font-bold text-muted">
          <IconPaw className="h-3.5 w-3.5 text-blue" /> 归属宠物（{d.pets.length}）
        </p>
        {d.pets.length === 0 ? (
          <p className="text-xs text-muted">该客户名下暂无宠物档案</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {d.pets.map((p) => (
              <PetRowBlock key={p.petId} pet={p} />
            ))}
          </ul>
        )}
      </section>

      <VisitsSection userId={d.userId} initial={d.visits} visitCount={d.visitCount} />

      <OrdersSection userId={d.userId} />

      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-line pt-3">
        <div className="flex flex-wrap gap-2.5">
          <ActionButton Icon={IconCalendar} label="新增预约" onClick={() => setBookOpen(true)} />
          <ActionButton Icon={IconMsg} label={chatBusy ? '正在打开…' : '发送消息'} onClick={() => void openChat()} />
          <ActionButton Icon={IconEdit} label="编辑资料" onClick={() => onEdit(d.userId)} />
        </div>
        <p className="text-[11px] text-muted">
          托管史：
          <button type="button" onClick={onNotReady('托管史')} className="ml-1 font-semibold text-blue underline-offset-2 hover:underline">
            功能暂未接入
          </button>
        </p>
      </div>

      {bookOpen && (
        <BookAppointmentModal
          customer={{ userId: d.userId, nickname: d.nickname, phoneMasked: d.phoneMasked, pets: d.pets }}
          // 刷新统一在关闭时：建单成功也走这里（若在成功那刻就 reload，360 的 loading 会把这弹窗连
          // 成功视图一起卸载重建——实测踩到）。未建单的关闭多刷一次无害（一个 detail 请求）。
          onClose={() => {
            setBookOpen(false)
            reload()
          }}
        />
      )}
    </div>
  )
}

function visitTone(status: string): Tone {
  if (status === 'COMPLETED') return 'green'
  if (status === 'CANCELLED') return 'gray'
  if (status === 'SERVING') return 'orange'
  if (status === 'PENDING_PAYMENT') return 'pink'
  return 'blue'
}

/* ---- 到店史：下拉收起 + 每页 10 条真分页（offset 下推，每次只查当页） ---- */

/** 每页固定 10 条：翻页时请求 limit=10&offset=k*10，服务端只返当页 */
const VISIT_PAGE_SIZE = 10

/**
 * 到店记录下拉：收起时只显摘要（累计次数 + 最近一次，取 detail 内嵌首条，零额外请求）；
 * 展开后按 10 条/页翻页，每次请求只查当页。服务端排序 service_date DESC, id DESC 前缀稳定。
 */
function VisitsSection({ userId, initial, visitCount }: { userId: string; initial: CustomerVisit[]; visitCount: string }) {
  const { push } = useToast()
  const [open, setOpen] = useState(false)
  const [page, setPage] = useState(0)
  const [rows, setRows] = useState<CustomerVisit[] | null>(null)
  const [hasMore, setHasMore] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const seq = useRef(0)

  const load = async (pageIdx: number) => {
    const my = ++seq.current
    setBusy(true)
    setError('')
    try {
      const data = await customersApi.appointments(userId, VISIT_PAGE_SIZE, pageIdx * VISIT_PAGE_SIZE)
      if (my !== seq.current) return
      if (data.length === 0 && pageIdx > 0) {
        // 总数恰为 10 的整数倍时，最后一页返回满 10 条 → 再点「下一页」才知道到底：回退并禁用
        setHasMore(false)
        push('没有更早的到店记录了', 'info')
        return
      }
      setRows(data)
      setPage(pageIdx)
      setHasMore(data.length === VISIT_PAGE_SIZE)
    } catch (err) {
      if (my !== seq.current) return
      setError(err instanceof BizError ? err.message : '加载失败，请重试')
    } finally {
      if (my === seq.current) setBusy(false)
    }
  }

  const toggle = () => {
    const next = !open
    setOpen(next)
    if (next && rows === null && initial.length > 0) void load(0)
  }

  const last = initial[0]
  return (
    <section>
      <button
        type="button"
        aria-expanded={open}
        onClick={toggle}
        className="flex w-full items-center gap-1.5 py-1 text-left"
      >
        <IconClock className="h-3.5 w-3.5 shrink-0 text-blue" />
        <span className="text-xs font-bold text-muted">到店记录</span>
        <span className="min-w-0 flex-1 truncate text-[11px] text-muted">
          累计 {visitCount} 次{!open && last ? ` · 最近 ${last.serviceDate} ${last.serviceName}` : ''}
        </span>
        <IconChevronDown className={cx('h-4 w-4 shrink-0 text-muted transition-transform', open && 'rotate-180')} />
      </button>

      {open && (
        <div className="mt-2">
          {initial.length === 0 ? (
            <p className="text-xs text-muted">暂无到店记录</p>
          ) : (
            <>
              {busy && rows === null && (
                <p className="px-3 py-6 text-center text-[11px] text-muted">
                  <Spinner /> 加载到店记录…
                </p>
              )}
              {error && (
                <div className="flex flex-col items-center gap-1.5 px-3 py-4">
                  <p className="text-[11px] text-[#E8506B]">加载失败：{error}</p>
                  <button
                    type="button"
                    onClick={() => void load(page)}
                    className="h-7 rounded-lg border border-line bg-white px-3 text-[11px] font-semibold text-ink hover:border-blue hover:text-blue"
                  >
                    重试
                  </button>
                </div>
              )}
              {rows && (
                <>
                  <ul className="divide-y divide-line overflow-hidden rounded-xl ring-1 ring-line" aria-busy={busy}>
                    {rows.length === 0 ? (
                      <li className="bg-white px-3 py-6 text-center text-xs text-muted">没有更早的到店记录了</li>
                    ) : (
                      rows.map((v) => (
                        <li key={v.appointmentId} className="flex flex-wrap items-center gap-x-4 gap-y-1 bg-white px-3 py-2.5 text-sm">
                          <span className="w-[92px] shrink-0 font-mono text-xs text-muted">{v.serviceDate}</span>
                          <span className="min-w-[110px] flex-1 truncate font-semibold text-ink">{v.serviceName}</span>
                          <span className="w-[92px] shrink-0 truncate text-xs text-muted">{v.employeeName ?? '未指派'}</span>
                          <span className="w-[86px] shrink-0 text-right font-bold text-ink">{v.amount === null ? '—' : fenToYuanText(v.amount)}</span>
                          <Badge label={VISIT_STATUS_LABEL[v.status] ?? v.status} tone={visitTone(v.status)} />
                        </li>
                      ))
                    )}
                  </ul>
                  <div className="mt-2 flex flex-wrap items-center justify-center gap-3">
                    <PagerButton disabled={busy || page <= 0} onClick={() => void load(page - 1)} dir="prev" />
                    <p className="text-[11px] text-muted">
                      第 {page + 1} 页{hasMore ? '' : '（最后一页）'} · 每页 {VISIT_PAGE_SIZE} 条
                    </p>
                    <PagerButton disabled={busy || !hasMore} onClick={() => void load(page + 1)} dir="next" />
                  </div>
                </>
              )}
            </>
          )}
        </div>
      )}
    </section>
  )
}

/* ---- 订单史（打通轮 P4）：下拉收起 + 服务端真实分页（真实 total，金额=创建时快照） ---- */

const ORDER_PAGE_SIZE = 10

function orderTone(status: string): Tone {
  if (status === 'COMPLETED' || status === 'SERVING') return 'green'
  if (status === 'PAID') return 'blue'
  if (status === 'WAIT_PAY') return 'orange'
  if (status === 'WAIT_SERVICE') return 'purple'
  if (status === 'CANCELLED' || status === 'REFUNDED') return 'gray'
  return 'pink'
}

/**
 * 客户订单史（打通轮 P4）：折叠态零请求（detail 不内嵌订单，摘要不谎报）；
 * 展开后按 10 条/页真实分页，排序 created_at DESC→id DESC，与订单管理页同数据口径。
 */
function OrdersSection({ userId }: { userId: string }) {
  const [open, setOpen] = useState(false)
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState<number | null>(null)
  const [rows, setRows] = useState<CustomerOrderRow[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const seq = useRef(0)

  const load = async (pageIdx: number) => {
    const my = ++seq.current
    setBusy(true)
    setError('')
    try {
      const data = await customersApi.orders(userId, pageIdx, ORDER_PAGE_SIZE)
      if (my !== seq.current) return
      setRows(data.list)
      setTotal(Number(BigInt(data.total)))
      setPage(pageIdx)
    } catch (err) {
      if (my !== seq.current) return
      setError(err instanceof BizError ? err.message : '加载失败，请重试')
    } finally {
      if (my === seq.current) setBusy(false)
    }
  }

  const toggle = () => {
    const next = !open
    setOpen(next)
    if (next && rows === null) void load(1)
  }

  const totalPages = total === null ? 1 : Math.max(1, Math.ceil(total / ORDER_PAGE_SIZE))

  return (
    <section>
      <button
        type="button"
        aria-expanded={open}
        onClick={toggle}
        className="flex w-full items-center gap-1.5 py-1 text-left"
      >
        <IconWallet className="h-3.5 w-3.5 shrink-0 text-blue" />
        <span className="text-xs font-bold text-muted">订单记录</span>
        <span className="min-w-0 flex-1 truncate text-[11px] text-muted">
          {total === null ? (open ? '正在读取…' : '展开查看本店订单与实收') : `共 ${total} 笔本店订单 · 金额按创建时快照`}
        </span>
        <IconChevronDown className={cx('h-4 w-4 shrink-0 text-muted transition-transform', open && 'rotate-180')} />
      </button>

      {open && (
        <div className="mt-2">
          {busy && rows === null && (
            <p className="px-3 py-6 text-center text-[11px] text-muted">
              <Spinner /> 加载订单记录…
            </p>
          )}
          {error && (
            <div className="flex flex-col items-center gap-1.5 px-3 py-4">
              <p className="text-[11px] text-[#E8506B]">加载失败：{error}</p>
              <button
                type="button"
                onClick={() => void load(page)}
                className="h-7 rounded-lg border border-line bg-white px-3 text-[11px] font-semibold text-ink hover:border-blue hover:text-blue"
              >
                重试
              </button>
            </div>
          )}
          {rows && (
            <>
              <ul className="divide-y divide-line overflow-hidden rounded-xl ring-1 ring-line" aria-busy={busy}>
                {rows.length === 0 ? (
                  <li className="bg-white px-3 py-6 text-center text-xs text-muted">该客户在本店还没有订单</li>
                ) : (
                  rows.map((o) => (
                    <li key={o.id} className="flex flex-wrap items-center gap-x-4 gap-y-1 bg-white px-3 py-2.5 text-sm">
                      <span className="w-[150px] shrink-0 font-mono text-xs text-muted">{o.orderNo}</span>
                      <span className="min-w-[110px] flex-1 truncate font-semibold text-ink">{o.serviceName ?? '—'}</span>
                      <span className="w-[76px] shrink-0 text-right font-bold text-ink">{fenToYuanText(o.amount)}</span>
                      <Badge label={ORDER_STATUS_LABEL[o.status as OrderStatus] ?? o.status} tone={orderTone(o.status)} />
                    </li>
                  ))
                )}
              </ul>
              {rows.length > 0 && totalPages > 1 && (
                <div className="mt-2 flex flex-wrap items-center justify-center gap-3">
                  <PagerButton disabled={busy || page <= 1} onClick={() => void load(page - 1)} dir="prev" />
                  <p className="text-[11px] text-muted">
                    第 {page}/{totalPages} 页 · 每页 {ORDER_PAGE_SIZE} 条
                  </p>
                  <PagerButton disabled={busy || page >= totalPages} onClick={() => void load(page + 1)} dir="next" />
                </div>
              )}
            </>
          )}
        </div>
      )}
    </section>
  )
}

/* ---- 宠物摘要：点开就地取 /store/pets/{id}（与宠物管理页共用同一端点） ---- */

/** 后端 PetErrorCode.PET_FORBIDDEN：宠物不在「本店预约关联 ∪ 在店托管」范围 */
const PET_FORBIDDEN = 20101

function PetRowBlock({ pet }: { pet: CustomerPetRef }) {
  const [open, setOpen] = useState(false)
  return (
    <li className="overflow-hidden rounded-xl ring-1 ring-line">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center gap-2.5 bg-blue-mist/60 px-3 py-2 text-left transition hover:bg-white"
      >
        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-white text-blue">
          {pet.species === 'DOG' ? <IconDog className="h-4 w-4" /> : pet.species === 'CAT' ? <IconCat className="h-4 w-4" /> : <IconPaw className="h-4 w-4" />}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[13px] font-bold text-ink">{pet.name}</span>
          <span className="block truncate text-[11px] text-muted">{pet.breed ?? '品种未登记'} · 点开看本店摘要</span>
        </span>
        <IconChevronDown className={cx('h-4 w-4 shrink-0 text-muted transition-transform', open && 'rotate-180')} />
      </button>
      {open && <PetSummary petId={pet.petId} />}
    </li>
  )
}

function PetSummary({ petId }: { petId: string }) {
  const { block, reload } = useAsync(() => petsApi.detail(petId), petId)
  if (block.state === 'loading') {
    return (
      <p className="border-t border-line bg-white px-3 py-4 text-center text-[11px] text-muted">
        <Spinner /> 加载宠物摘要…
      </p>
    )
  }
  if (block.state === 'error') {
    const hint =
      block.code === PET_FORBIDDEN
        ? '该宠物不在本店可见范围（本店可见 = 有本店预约或在店托管记录）——这是范围口径，不是故障。'
        : block.code === BizCode.FORBIDDEN
          ? '当前账号无宠物查看权限（pet:view）。'
          : null
    return (
      <div className="flex flex-col items-center gap-1.5 border-t border-line bg-white px-3 py-4 text-center">
        <p className={cx('text-[11px] leading-relaxed', hint ? 'text-muted' : 'text-[#E8506B]')}>{hint ?? `宠物摘要加载失败：${block.message}`}</p>
        {!hint && (
          <button type="button" onClick={reload} className="h-7 rounded-lg border border-line px-3 text-[11px] font-semibold text-ink hover:border-blue hover:text-blue">
            重试
          </button>
        )}
      </div>
    )
  }
  const d = block.data
  const p = d.pet
  return (
    <div className="flex flex-col gap-2 border-t border-line bg-white p-3">
      <div className="grid gap-x-5 gap-y-2 sm:grid-cols-2">
        <Field label="物种 / 品种" value={`${SPECIES_LABEL[p.species] ?? p.species} · ${p.breed ?? '品种未登记'}`} />
        <Field label="性别 / 绝育" value={`${p.gender === 'MALE' ? '公' : p.gender === 'FEMALE' ? '母' : '未登记'} · ${p.isNeutered === null ? '绝育未登记' : p.isNeutered ? '已绝育' : '未绝育'}`} />
        <Field label="体重 / 年龄" value={`${petWeightText(p.weightKg)} · ${petAgeText(p.birthday, p.birthdayApprox)}`} />
        <Field label="档案状态" value={`${PET_STATUS_LABEL[p.status] ?? p.status}${p.remark ? ` · 备注：${p.remark}` : ''}`} />
      </div>
      <SummaryLine label="最近服务">{lastServiceText(d.lastService)}</SummaryLine>
      <SummaryLine label="在店托管">{boardingText(d.boarding)}</SummaryLine>
      <SummaryLine label="健康摘要">{healthSummaryText(d.healthSummary)}</SummaryLine>
      <SummaryLine label="健康明细">{healthDetailText(d.health)}</SummaryLine>
    </div>
  )
}

function SummaryLine({ label, children }: { label: string; children: ReactNode }) {
  return (
    <p className="text-[11px] leading-relaxed text-muted">
      <span className="font-semibold text-ink">{label}：</span>
      {children}
    </p>
  )
}

/** 与宠物管理页 ServicePanel 同一套文案口径：SERVICE=履约真实完成，APPOINTMENT=预约快照兜底。 */
function lastServiceText(s: PetLastService | null): string {
  if (!s) return '暂无服务记录'
  const label = s.source === 'SERVICE' ? (s.label === 'BATH' ? '洗澡服务' : s.label === 'WALK' ? '遛狗服务' : s.label ?? '服务') : s.label ?? '预约'
  const date = (s.source === 'SERVICE' ? s.finishedAt?.slice(0, 10) : s.serviceDate) ?? '日期未定'
  const done = s.source === 'SERVICE' || s.status === 'COMPLETED'
  return `${label} · ${date}（${done ? '已完成' : '预约中'}）`
}

function boardingText(b: PetBoardingBrief | null): string {
  if (!b) return '当前不在店'
  return `${b.status === 'CHECKED_IN' ? '已入住' : '每日护理中'} · 入住 ${b.checkinDate} · 计划离店 ${b.checkoutDate}`
}

function healthSummaryText(h: PetHealthSummary | null): string {
  if (!h) return '无健康摘要'
  const parts = [
    h.allergyCount > 0 ? `过敏 ${h.allergyCount} 项` : null,
    h.chronicCount > 0 ? `慢性病 ${h.chronicCount} 项` : null,
    h.hasSpecialNotes ? '有特殊照护备注' : null,
    h.vaccineSummary ? `疫苗：${h.vaccineSummary}` : null,
  ].filter(Boolean)
  return parts.length ? parts.join(' · ') : '无过敏 / 慢病 / 特殊备注'
}

/** null 是三重语义（无权限 / 无档案 / 存储不可达），如实并列可能原因，不猜是哪一种。 */
function healthDetailText(h: PetHealthDetail | null): string {
  if (!h) return '未提供（无 pet:health:view 权限，或该宠物尚无健康档案）'
  const parts = [
    h.allergies?.length ? `过敏原：${h.allergies.join('、')}` : null,
    h.chronicDiseases?.length ? `慢性病：${h.chronicDiseases.join('、')}` : null,
    h.specialNotes ? `特殊备注：${h.specialNotes}` : null,
  ].filter(Boolean)
  const meta = h.updatedAt ? `（${h.updatedByRole ?? '未知角色'} 更新于 ${h.updatedAt.slice(0, 10)}）` : ''
  return (parts.length ? parts.join('；') : '无记录内容') + meta
}

/* ---- 右栏（概览降权：列表才是本页主角，卡片只做紧凑摘要） ---- */

function StatsCard({ block, onRetry }: { block: Block<CustomerStats>; onRetry: () => void }) {
  const boxes: { key: string; label: string; value: string; Icon: typeof IconUsers }[] =
    block.state === 'ready'
      ? [
          { key: 'total', label: '本店客户', value: block.data.totalCustomers, Icon: IconUsers },
          { key: 'new', label: '近30天新增', value: block.data.newLast30Days, Icon: IconTrendUp },
          { key: 'boarding', label: '托管客户', value: block.data.inBoardingCustomers, Icon: IconBox },
        ]
      : []
  return (
    <section className="rounded-2xl bg-white p-4 shadow-soft ring-1 ring-line" aria-label="客户数据概览">
      <h2 className="text-sm font-bold text-ink">客户数据概览</h2>
      {block.state === 'loading' && (
        <p className="py-4 text-center text-xs text-muted">
          <Spinner /> 加载中…
        </p>
      )}
      {block.state === 'error' && (
        <div className="flex flex-col items-center gap-2 py-4">
          <p className="text-xs text-[#E8506B]">{block.message}</p>
          <button type="button" onClick={onRetry} className="h-7 rounded-lg border border-line px-3 text-[11px] font-semibold text-ink hover:border-blue hover:text-blue">
            重试
          </button>
        </div>
      )}
      {block.state === 'ready' && (
        <>
          <div className="mt-3 grid grid-cols-3 gap-2">
            {boxes.map(({ key, label, value, Icon }) => (
              <div key={key} className="flex flex-col items-center gap-1 rounded-xl bg-blue-mist/60 py-2.5 ring-1 ring-line">
                <Icon className="h-4 w-4 text-blue" />
                <p className="text-base font-black leading-none text-ink">{value}</p>
                <p className="text-[11px] text-muted">{label}</p>
              </div>
            ))}
          </div>
          <p className="mt-2.5 text-[11px] leading-relaxed text-muted">
            复购率与客单价尚无统计口径（后端契约明确不含），此处不放数字。
          </p>
        </>
      )}
    </section>
  )
}

function FaqCard() {
  const [openId, setOpenId] = useState<string | null>(null)
  return (
    <section className="rounded-2xl bg-white p-5 shadow-soft ring-1 ring-line">
      <h2 className="mb-2 flex items-center gap-2 text-base font-bold text-ink">
        <span className="grid h-7 w-7 place-items-center rounded-full bg-[#FFF1E0] text-[#F59E0B]">
          <IconHelp className="h-4 w-4" />
        </span>
        常见问题
      </h2>
      <ul className="divide-y divide-line">
        {FAQS.map(({ id, q, a }) => (
          <li key={id}>
            <button
              type="button"
              aria-expanded={openId === id}
              onClick={() => setOpenId((v) => (v === id ? null : id))}
              className="flex w-full items-center justify-between gap-3 py-3 text-left"
            >
              <span className="flex min-w-0 items-center gap-2 text-[13px] font-semibold text-ink">
                <i className="h-1.5 w-1.5 shrink-0 rotate-45 bg-blue" />
                <span className="truncate">{q}</span>
              </span>
              <IconChevronRight className={cx('h-4 w-4 shrink-0 text-muted transition-transform', openId === id && 'rotate-90')} />
            </button>
            {openId === id && <p className="pb-3 pl-5 text-xs leading-relaxed text-muted">{a}</p>}
          </li>
        ))}
      </ul>
    </section>
  )
}

/* ---- 通用小件 ---- */

const AVATAR_TONES: Tone[] = ['blue', 'green', 'purple', 'orange', 'pink']

function Avatar({ name, id, url }: { name: string; id: string; url: string | null }) {
  const tone = AVATAR_TONES[Number(BigInt(id) % BigInt(AVATAR_TONES.length))]
  const [failed, setFailed] = useState(false)
  // presigned GET 指向的对象可能不存在（intent 已签发但 PUT 未成功），加载失败必须回落占位，
  // 否则界面上是一个破图图标——比"没有头像"更像一个故障。
  if (url && !failed) return <img src={url} alt={name} className="h-11 w-11 shrink-0 rounded-full object-cover" onError={() => setFailed(true)} />
  return (
    <span className="grid h-11 w-11 shrink-0 place-items-center rounded-full text-[15px] font-black" style={{ background: TONE[tone].bg, color: TONE[tone].fg }}>
      {customerInitial(name)}
    </span>
  )
}

function Badge({ label, tone }: { label: string; tone: Tone }) {
  return (
    <span className="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold" style={{ background: TONE[tone].bg, color: TONE[tone].fg }}>
      {label}
    </span>
  )
}

function Field({ label, value, muted }: { label: string; value: string; muted?: boolean }) {
  return (
    <div>
      <p className="text-[11px] text-muted">{label}</p>
      <p className={cx('mt-0.5 text-sm font-semibold', muted ? 'text-muted' : 'text-ink')}>{value}</p>
    </div>
  )
}

function ActionButton({ Icon, label, onClick }: { Icon: typeof IconEdit; label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex h-9 items-center gap-1.5 rounded-lg bg-white px-3.5 text-xs font-semibold text-ink ring-1 ring-line transition hover:text-blue hover:ring-blue"
    >
      <Icon className="h-4 w-4" /> {label}
    </button>
  )
}

function Spinner() {
  return <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-blue border-t-transparent align-[-2px]" aria-hidden="true" />
}

function PagerButton({ dir, disabled, onClick }: { dir: 'prev' | 'next'; disabled: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={dir === 'prev' ? '上一页' : '下一页'}
      className={cx(
        'grid h-8 w-8 place-items-center rounded-lg border bg-white transition',
        disabled ? 'cursor-not-allowed border-line text-muted/50' : 'border-line text-ink hover:border-blue hover:text-blue',
      )}
    >
      <IconChevronRight className={cx('h-4 w-4', dir === 'prev' && 'rotate-180')} />
    </button>
  )
}

function ListLoading() {
  return (
    <div className="flex flex-col gap-3" role="status" aria-label="客户列表加载中">
      {[0, 1, 2].map((i) => (
        <div key={i} className="flex h-[76px] items-center justify-center rounded-2xl bg-blue-mist/60 ring-1 ring-line">
          {i === 0 && (
            <span className="text-xs text-muted">
              <Spinner /> 加载客户列表…
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
      <p className="text-sm text-[#E8506B]">客户列表加载失败：{message}</p>
      <button type="button" onClick={onRetry} className="h-9 rounded-lg border border-line bg-white px-5 text-xs font-semibold text-ink transition hover:border-blue hover:text-blue">
        重试
      </button>
      <p className="max-w-[420px] text-center text-[11px] leading-relaxed text-muted">
        本页直连真实接口，不再回退演示数据。常见原因：当前账号未被授予 <span className="font-mono">customer:view:store</span>（10003）、
        未选择有效门店（10002）、后端 <span className="font-mono">/api/v1/store/customers</span> 未部署（网络错误）。
      </p>
    </div>
  )
}
