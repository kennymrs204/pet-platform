import { useEffect, useMemo, useState, type ChangeEvent, type ReactNode } from 'react'
import { IconMapPin } from '../../components/ui/icons'
import PanelGroup from '../../components/settings/PanelGroup'
import { Badge, BlockShell, GHOST, INPUT, Labeled, NAVY, type Block } from '../../components/settings/SettingsKit'
import { useToast } from '../../components/ui/Toast'
import {
  STORE_STATUS_LABEL,
  buildStoreInfoUpdate,
  settingsApi,
  storeInfoProblem,
  timeInputValue,
  type StoreInfo,
  type StoreInfoFormState,
  type StoreSettings,
} from '../../api/settings'
import { BizError } from '../../api/types'
import { cx } from '../../lib/cx'
import type { GroupCtl } from '../../components/settings/SettingsKit'

/** 分组 id 单一来源：页面分类表与此处渲染都引用它，避免字符串各写一份而漂移。 */
export const STORE_GROUPS = { info: 'store-info' } as const

/**
 * A 门店设置：门店资料 + 营业时间 + 公告。
 * **只有一个表单、一个保存按钮**——`PUT /store/info` 是显式列名全量写，
 * 拆成两块独立保存就会有一块少带键把另一块的列写成 NULL。
 */
export default function StoreInfoPanel({ ctl, block, storeId, onRetry, onReload }: { ctl: GroupCtl; block: Block<StoreSettings>; storeId: string | null; onRetry: () => void; onReload: () => void }) {
  return (
    <BlockShell block={block} onRetry={onRetry} deniedHint="需要 store:info:update（店长/平台管理员）">
      {(data: StoreSettings) => (
        <StoreInfoForm key={`${storeId}-${data.store.id}`} ctl={ctl} store={data.store} onSaved={onReload} />
      )}
    </BlockShell>
  )
}

function StoreInfoForm({ ctl, store, onSaved }: { ctl: GroupCtl; store: StoreInfo; onSaved: () => void }) {
  const { push } = useToast()
  const [saving, setSaving] = useState(false)
  const initial = useMemo<StoreInfoFormState>(
    () => ({
      name: store.name,
      phone: store.phone,
      address: store.address,
      businessStart: timeInputValue(store.businessStartTime),
      businessEnd: timeInputValue(store.businessEndTime),
      breakStart: timeInputValue(store.breakStartTime),
      breakEnd: timeInputValue(store.breakEndTime),
      announcement: store.announcement ?? '',
    }),
    [store],
  )
  const [form, setForm] = useState<StoreInfoFormState>(initial)
  const set = (k: keyof StoreInfoFormState) => (e: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
    setForm((f) => ({ ...f, [k]: e.target.value }))

  const dirty = Object.keys(initial).some((k) => form[k as keyof StoreInfoFormState] !== initial[k as keyof StoreInfoFormState])
  const groupId = STORE_GROUPS.info
  useEffect(() => {
    ctl.setDirty(groupId, dirty)
  }, [ctl, groupId, dirty])

  const save = async () => {
    const problem = storeInfoProblem(form)
    if (problem) {
      push(problem, 'error')
      return
    }
    setSaving(true)
    try {
      await settingsApi.updateInfo(buildStoreInfoUpdate(store, form))
      push('门店资料已保存', 'success')
      onSaved()
    } catch (err) {
      push(err instanceof BizError ? err.message : '保存失败，请稍后重试', 'error')
    } finally {
      setSaving(false)
    }
  }

  const open = ctl.isOpen(groupId)
  return (
    <PanelGroup
      id={groupId}
      Icon={IconMapPin}
      title="门店资料与营业时间"
      open={open}
      dirty={dirty}
      onToggle={() => ctl.toggle(groupId)}
      meta={<Badge label={STORE_STATUS_LABEL[store.status] ?? store.status} tone={store.status === 'OPEN' ? 'green' : 'gray'} />}
      summary={`营业 ${form.businessStart || '—'}–${form.businessEnd || '—'} · 午休 ${form.breakStart && form.breakEnd ? `${form.breakStart}–${form.breakEnd}` : '无'} · 公告${form.announcement.trim() ? `${form.announcement.trim().length} 字` : '空'}`}
    >
      <div className="flex flex-col gap-5">
        <FormSection title="基础信息">
          <Labeled label="门店名称" required>
            <input className={INPUT} value={form.name} onChange={set('name')} maxLength={100} />
          </Labeled>
          <Labeled label="联系电话" required>
            <input className={INPUT} value={form.phone} onChange={set('phone')} maxLength={20} />
          </Labeled>
          <Labeled label="门店地址" required className="sm:col-span-2">
            <input className={INPUT} value={form.address} onChange={set('address')} maxLength={255} />
          </Labeled>
        </FormSection>

        <FormSection title="营业时间与公告">
          <Labeled label="营业开始" required>
            <input type="time" className={INPUT} value={form.businessStart} onChange={set('businessStart')} />
          </Labeled>
          <Labeled label="营业结束" required>
            <input type="time" className={INPUT} value={form.businessEnd} onChange={set('businessEnd')} />
          </Labeled>
          <Labeled label="休息开始（可不填）">
            <input type="time" className={INPUT} value={form.breakStart} onChange={set('breakStart')} />
          </Labeled>
          <Labeled label="休息结束（与上项成对）">
            <input type="time" className={INPUT} value={form.breakEnd} onChange={set('breakEnd')} />
          </Labeled>
          <Labeled label="门店公告（≤500 字）" className="sm:col-span-2">
            <textarea className={`${INPUT} h-20 py-2 leading-relaxed`} value={form.announcement} onChange={set('announcement')} maxLength={500} rows={3} />
          </Labeled>
        </FormSection>

        <div className="flex flex-wrap items-center gap-3">
          <button type="button" disabled={saving} onClick={() => void save()} className={cx('flex h-10 items-center gap-2 rounded-xl px-5 text-sm font-bold transition disabled:opacity-60', NAVY)}>
            {saving ? '保存中…' : '保存门店资料'}
          </button>
          <button type="button" disabled={saving || !dirty} onClick={() => setForm(initial)} className={cx('h-10 rounded-xl px-4 text-sm font-semibold transition disabled:opacity-45', GHOST)}>
            放弃修改
          </button>
          <span className="text-[11px] text-muted">
            门店类型
            <span className="ml-1 font-semibold text-ink">{store.storeType === 'PLATFORM_HQ' ? '平台总部' : '普通门店'}</span>
            ，与经营状态均不可在此修改
          </span>
        </div>

        <p className="text-[11px] leading-relaxed text-muted">
          公告会存进门店档案，但顾客端门店卡当前不展示该字段（读侧尚无消费者）；营业时间/午休改动则立即反映在顾客可约时段上。
        </p>
      </div>
    </PanelGroup>
  )
}

function FormSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <p className="mb-2.5 border-l-2 border-blue-soft pl-2 text-[12px] font-bold text-ink">{title}</p>
      <div className="grid gap-4 sm:grid-cols-2">{children}</div>
    </div>
  )
}
