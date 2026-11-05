import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { authApi } from '../api/auth'
import { BizCode, BizError } from '../api/types'
import { CustomerProfileView, buildProfileUpdate, displayName, profileApi } from '../api/profile'
import { petApi } from '../api/pet'
import { tokenStore } from '../auth/tokenStore'
import { session } from '../auth/session'
import { resetUnreadCount, useUnreadCount } from '../lib/unreadCount'
import { useBizFail } from '../auth/useBizFail'
import BottomNav from '../components/layout/BottomNav'
import AvatarPicker from '../components/ui/AvatarPicker'
import { useToast } from '../components/ui/Toast'
import { IconBell, IconCalendar, IconChevronRight, IconHouse, IconList, IconLogOut, IconPaw, IconUser } from '../components/ui/icons'
import { cx } from '../lib/cx'
import { invalidateProfile, useCustomerProfile } from '../lib/useCustomerProfile'
import { usePublicStores } from '../lib/usePublicStores'

const PASSWORD_RE = /^(?=.*[A-Za-z])(?=.*\d)\S{8,20}$/

/**
 * 「我的」页。真能力四件：账号资料（ADR-027 GET/PUT /customer/profile）、
 * 宠物档案（/customer/pets）、我的订单/我的托管入口、修改密码（/auth/password/change）。
 * 头像可上传：本地图片经 `/uploads/intent`（scene=USER_AVATAR）presigned PUT 直传对象存储，
 * 存回 `avatar`=对象 key（ADR-029），展示用后端签发的 `avatarUrl`。
 * 消息通知已接真接口（ADR-041 GET /customer/notifications）：进本页即拉未读数，行上直接显计数，点击进消息中心。
 */
export default function ProfilePage() {
  const navigate = useNavigate()
  const fail = useBizFail()
  const userId = tokenStore.getUserId()
  const { profile, reload } = useCustomerProfile()
  const { nameOf: storeName } = usePublicStores()

  const [petCount, setPetCount] = useState<number | null>(null)
  const [changing, setChanging] = useState(false)
  const [editing, setEditing] = useState(false)
  /** 签名头像读失败时记下那个 url 并回落图标（600s 过期是常态）；记 url 不记布尔，换签名后要能再试 */
  const [failedAvatar, setFailedAvatar] = useState<string | null>(null)

  useEffect(() => {
    if (!userId) navigate('/login', { replace: true })
  }, [userId, navigate])

  useEffect(() => {
    const ac = new AbortController()
    petApi
      .list()
      .then((d) => {
        if (!ac.signal.aborted) setPetCount(Array.isArray(d) ? d.length : 0)
      })
      .catch(() => {
        if (!ac.signal.aborted) setPetCount(null)
      })
    return () => ac.abort()
  }, [userId])

  const logout = async () => {
    try {
      await authApi.logout()
    } catch {
      // 登出失败也清理本地会话，access 自然过期兜底
    }
    session.bump() // 登出前作废在途 refresh，防其把会话重建回来
    tokenStore.clear()
    resetUnreadCount() // 未读数随会话一起清零：换账号不能带着上一个人的红点
    navigate('/login', { replace: true })
  }

  /** 消息未读（模块级缓存，见 lib/unreadCount）：total 为两源合计，分账用于本页「消息通知」行说明 */
  const { count: unread, summary } = useUnreadCount()

  return (
    <main className="min-h-[100dvh] bg-brand-gradient">
      <div className="mx-auto max-w-md px-4 pb-32">
        <section className="animate-fade-up mt-6 rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5">
          <div className="flex items-center gap-3">
            {profile?.avatarUrl && failedAvatar !== profile.avatarUrl ? (
              <img
                src={profile.avatarUrl}
                alt=""
                onError={() => setFailedAvatar(profile.avatarUrl!)}
                className="h-14 w-14 shrink-0 rounded-2xl object-cover ring-1 ring-navy/10"
              />
            ) : (
              <span className="grid h-14 w-14 shrink-0 place-items-center rounded-2xl bg-btn-gradient text-gold-light">
                <IconUser className="h-7 w-7" />
              </span>
            )}
            <div className="min-w-0 flex-1">
              <p className="flex items-center gap-2">
                <span className="truncate text-[15px] font-bold text-ink">{displayName(profile)}</span>
                {profile?.status && (
                  <span
                    className={cx(
                      'shrink-0 rounded-full px-2 py-0.5 text-[10px] font-semibold',
                      profile.status === 'NORMAL'
                        ? 'bg-emerald-50 text-emerald-600 ring-1 ring-emerald-200/70'
                        : 'bg-rose-50 text-rose-600 ring-1 ring-rose-200/70',
                    )}
                  >
                    {profile.status === 'NORMAL' ? '账号正常' : profile.status}
                  </span>
                )}
              </p>
              <p className="mt-0.5 truncate text-[11px] text-muted">
                {profile?.phone ? `手机 ${profile.phone}` : `用户 ID ${userId}`}
                {profile?.registeredAt ? ` · ${profile.registeredAt.slice(0, 10)} 注册` : ''}
              </p>
            </div>
            <button
              type="button"
              onClick={() => setEditing((v) => !v)}
              className="shrink-0 rounded-xl bg-warmgray px-3 py-2 text-[11px] font-semibold text-navy/80"
            >
              {editing ? '收起' : '编辑资料'}
            </button>
          </div>

          {profile && !editing && (
            <dl className="mt-3 grid grid-cols-2 gap-x-3 gap-y-2.5 border-t border-navy/5 pt-3 text-xs">
              <Item k="常用门店" v={profile.preferredStoreId ? storeName(profile.preferredStoreId) : '未设置'} />
              <Item k="默认联系电话" v={profile.defaultContactPhone || '未设置（下单时按账号手机号联系）'} />
              <Item k="活动通知" v={profile.marketingOptIn ? '接收' : '不接收'} />
            </dl>
          )}
        </section>

        {editing && <ProfileEditForm profile={profile} storeName={storeName} onSaved={() => { setEditing(false); reload() }} onError={fail} />}

        <section className="animate-fade-up mt-4 overflow-hidden rounded-3xl bg-white/90 shadow-soft ring-1 ring-navy/5" style={{ animationDelay: '70ms' }}>
          <Row
            Icon={IconPaw}
            label="我的宠物"
            desc={petCount === null ? '档案加载中…' : petCount === 0 ? '还没有宠物档案' : `${petCount} 只毛孩子`}
            onClick={() => navigate('/pets')}
          />
          <Row
            Icon={IconList}
            label="我的订单"
            desc="待支付、待服务、历史记录"
            onClick={() => navigate('/orders')}
          />
          <Row
            Icon={IconHouse}
            label="我的托管"
            desc="入住进度与每日照护记录"
            onClick={() => navigate('/boarding')}
          />
          <Row
            Icon={IconBell}
            label="消息通知"
            desc={
              (unread ?? 0) > 0
                ? `${summary?.notificationCount ?? 0} 条系统通知 · ${summary?.chatCount ?? 0} 条门店消息`
                : '退款进度、预约通知与门店沟通'
            }
            onClick={() => navigate('/messages')}
            last
          />
        </section>

        <section className="animate-fade-up mt-4 overflow-hidden rounded-3xl bg-white/90 shadow-soft ring-1 ring-navy/5" style={{ animationDelay: '140ms' }}>
          <Row
            Icon={IconCalendar}
            label="修改密码"
            desc="修改后其他设备需重新登录"
            onClick={() => setChanging((v) => !v)}
            last
          />
          {changing && <ChangePasswordForm onDone={() => setChanging(false)} onError={fail} />}
        </section>

        <button
          type="button"
          onClick={() => void logout()}
          className="animate-fade-up mt-4 flex h-12 w-full items-center justify-center gap-1.5 rounded-2xl bg-white/70 text-sm font-semibold text-navy/80 ring-1 ring-navy/5"
          style={{ animationDelay: '210ms' }}
        >
          <IconLogOut className="h-4 w-4" /> 退出登录
        </button>

        <p className="mt-4 px-1 text-[11px] leading-relaxed text-muted">
          手机号与账号状态不可在这里改（换绑手机是独立流程，本期未开）；改密码在下方。
          头像在「编辑资料」里选本地图片直传，保存后本页与 B 端都会显示。
        </p>
      </div>
      <BottomNav active="mine" />
    </main>
  )
}

/**
 * 资料编辑（ADR-027 §3：PUT 全量提交）。
 * 提交体走 `buildProfileUpdate`，它会**把 avatar 原样带回**——漏传即被后端清空。
 */
function ProfileEditForm({
  profile,
  storeName,
  onSaved,
  onError,
}: {
  profile: CustomerProfileView | null
  storeName: (id: string) => string
  onSaved: () => void
  onError: (e: unknown) => void
}) {
  const { push } = useToast()
  const { stores } = usePublicStores()
  const [nickname, setNickname] = useState(profile?.nickname ?? '')
  const [avatarKey, setAvatarKey] = useState<string | null>(profile?.avatar ?? null)
  const [contact, setContact] = useState(profile?.defaultContactPhone ?? '')
  const [preferred, setPreferred] = useState(profile?.preferredStoreId ?? '')
  const [marketing, setMarketing] = useState(profile?.marketingOptIn ?? false)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')

  const submit = async () => {
    if (nickname.trim().length > 50) return setErr('昵称不超过 50 字')
    if (contact.trim() && !/^1[3-9]\d{9}$/.test(contact.trim())) return setErr('默认联系电话须为 11 位手机号，或留空')
    setErr('')
    setBusy(true)
    try {
      await profileApi.update(
        buildProfileUpdate({ avatar: avatarKey, preferredStoreId: profile?.preferredStoreId ?? null }, {
          nickname,
          defaultContactPhone: contact,
          preferredStoreId: preferred,
          marketingOptIn: marketing,
        }),
      )
      invalidateProfile()
      push('资料已保存', 'success')
      onSaved()
    } catch (e) {
      onError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="animate-fade-up mt-4 rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5">
      <h2 className="text-sm font-bold text-ink">编辑资料</h2>

      <div className="mt-3 flex items-center gap-3">
        <AvatarPicker
          scene="USER_AVATAR"
          value={avatarKey}
          previewUrl={avatarKey === (profile?.avatar ?? null) ? profile?.avatarUrl ?? null : null}
          onPicked={(key) => setAvatarKey(key)}
          onError={(msg) => setErr(msg)}
        />
        <p className="min-w-0 flex-1 text-[11px] leading-relaxed text-muted">
          点头像选本地图片，直传对象存储后随「保存资料」一起提交；仅支持 JPG/PNG/WebP，5MB 内。
        </p>
      </div>

      <label className="mt-3 block">
        <span className="mb-1 block px-1 text-[11px] font-semibold text-navy/70">昵称（留空则显示「用户+手机号尾4位」）</span>
        <input
          value={nickname}
          onChange={(e) => {
            setNickname(e.target.value)
            setErr('')
          }}
          maxLength={60}
          placeholder="如：林女士"
          className="w-full rounded-2xl bg-warmgray/70 px-3.5 py-2.5 text-sm text-ink ring-1 ring-navy/5 outline-none focus:ring-2 focus:ring-navy/25"
        />
      </label>

      <label className="mt-3 block">
        <span className="mb-1 block px-1 text-[11px] font-semibold text-navy/70">默认联系电话（门店联系用，留空=按账号手机号）</span>
        <input
          value={contact}
          onChange={(e) => {
            setContact(e.target.value.replace(/\D/g, '').slice(0, 11))
            setErr('')
          }}
          inputMode="numeric"
          placeholder="11 位手机号"
          className="w-full rounded-2xl bg-warmgray/70 px-3.5 py-2.5 text-sm text-ink ring-1 ring-navy/5 outline-none focus:ring-2 focus:ring-navy/25"
        />
      </label>

      <div className="mt-3">
        <span className="mb-1 block px-1 text-[11px] font-semibold text-navy/70">常用门店（仅偏好，不影响下单门店）</span>
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            onClick={() => setPreferred('')}
            className={cx(
              'rounded-2xl px-3 py-2 text-[11px] font-semibold',
              preferred === '' ? 'bg-btn-gradient text-white' : 'bg-warmgray text-navy/70',
            )}
          >
            不设置
          </button>
          {stores.map((s) => (
            <button
              key={s.id}
              type="button"
              onClick={() => setPreferred(String(s.id))}
              className={cx(
                'rounded-2xl px-3 py-2 text-[11px] font-semibold',
                preferred === String(s.id) ? 'bg-btn-gradient text-white' : 'bg-warmgray text-navy/70',
              )}
            >
              {s.name}
            </button>
          ))}
        </div>
      </div>

      <label className="mt-3 flex items-center gap-2 px-1 text-xs text-muted">
        <input
          type="checkbox"
          checked={marketing}
          onChange={(e) => setMarketing(e.target.checked)}
          className="h-4 w-4 accent-navy"
        />
        接收活动与优惠通知
      </label>

      {preferred && !stores.some((s) => String(s.id) === preferred) && (
        <p className="mt-2 text-[11px] text-muted">当前常用门店：{storeName(preferred)}</p>
      )}
      {err && <p className="mt-2 text-[11px] font-medium text-rose-500">{err}</p>}

      <button
        type="button"
        disabled={busy}
        onClick={() => void submit()}
        className={cx(
          'mt-4 h-11 w-full rounded-2xl bg-btn-gradient text-sm font-semibold text-white shadow-btn',
          busy ? 'opacity-60' : 'active:scale-[0.99]',
        )}
      >
        {busy ? '保存中…' : '保存资料'}
      </button>
    </section>
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

function Row({
  Icon,
  label,
  desc,
  onClick,
  last,
}: {
  Icon: (p: { className?: string }) => JSX.Element
  label: string
  desc: string
  onClick: () => void
  last?: boolean
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cx(
        'flex w-full items-center gap-3 px-4 py-3.5 text-left transition hover:bg-ivory/60',
        !last && 'border-b border-navy/5',
      )}
    >
      <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-ivory text-gold-dark ring-1 ring-gold/25">
        <Icon className="h-[18px] w-[18px]" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block text-sm font-semibold text-ink">{label}</span>
        <span className="mt-0.5 block truncate text-[11px] text-muted">{desc}</span>
      </span>
      <IconChevronRight className="h-4 w-4 shrink-0 text-muted" />
    </button>
  )
}

/** 修改密码（POST /auth/password/change，需登录态）。旧密码错 → 20004。 */
function ChangePasswordForm({ onDone, onError }: { onDone: () => void; onError: (e: unknown) => void }) {
  const { push } = useToast()
  const [oldPwd, setOldPwd] = useState('')
  const [newPwd, setNewPwd] = useState('')
  const [again, setAgain] = useState('')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    if (!oldPwd) return setErr('请输入原密码')
    if (!PASSWORD_RE.test(newPwd)) return setErr('新密码须 8-20 位，包含字母和数字，且不含空格')
    if (newPwd !== again) return setErr('两次输入的新密码不一致')
    setErr('')
    setBusy(true)
    try {
      await authApi.changePassword(oldPwd, newPwd)
      push('密码已修改，请用新密码重新登录', 'success')
      onDone()
      // 后端改密会清空 refresh 白名单，本地会话已不可续期，主动回登录页
      session.bump()
      tokenStore.clear()
      window.setTimeout(() => window.location.assign('/login'), 900)
    } catch (e) {
      if (e instanceof BizError && e.code === BizCode.PASSWORD_INCORRECT) setErr('原密码不正确')
      else onError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-2.5 border-t border-navy/5 px-4 py-4">
      {[
        { v: oldPwd, set: setOldPwd, ph: '原密码', type: 'password' },
        { v: newPwd, set: setNewPwd, ph: '新密码（8-20 位，含字母和数字）', type: 'password' },
        { v: again, set: setAgain, ph: '再次输入新密码', type: 'password' },
      ].map((f) => (
        <input
          key={f.ph}
          type={f.type}
          value={f.v}
          onChange={(e) => {
            f.set(e.target.value)
            setErr('')
          }}
          placeholder={f.ph}
          className="w-full rounded-2xl bg-warmgray/70 px-3.5 py-2.5 text-sm text-ink outline-none ring-1 ring-navy/5 focus:ring-2 focus:ring-navy/25"
        />
      ))}
      {err && <p className="text-[11px] font-medium text-rose-500">{err}</p>}
      <div className="flex gap-2">
        <button
          type="button"
          onClick={() => {
            setErr('')
            onDone()
          }}
          className="h-10 w-20 rounded-2xl bg-warmgray text-xs font-semibold text-navy/80"
        >
          收起
        </button>
        <button
          type="button"
          disabled={busy}
          onClick={() => void submit()}
          className="h-10 flex-1 rounded-2xl bg-btn-gradient text-xs font-semibold text-white shadow-btn disabled:opacity-60"
        >
          {busy ? '提交中…' : '确认修改'}
        </button>
      </div>
    </div>
  )
}
