import { useEffect, useSyncExternalStore, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { IconLock, IconUser } from '../../components/ui/icons'
import PanelGroup from '../../components/settings/PanelGroup'
import { INPUT, Labeled, NAVY, type GroupCtl } from '../../components/settings/SettingsKit'
import { useToast } from '../../components/ui/Toast'
import { session } from '../../auth/session'
import { storeContext, storeLabel } from '../../auth/storeContext'
import { tokenStore } from '../../auth/tokenStore'
import { authApi } from '../../api/auth'
import { BizCode, BizError } from '../../api/types'
import { passwordProblem } from '../../api/settings'
import { cx } from '../../lib/cx'

export const ACCOUNT_GROUPS = { me: 'account-me', password: 'account-password' } as const

/** F 账号与安全：身份摘要 + 改密（改密后的会话收敛动作与原来一致）。 */
export default function AccountPanel({ ctl }: { ctl: GroupCtl }) {
  const me = ACCOUNT_GROUPS.me
  const pwd = ACCOUNT_GROUPS.password
  return (
    <div className="flex flex-col gap-4">
      <CurrentAccount id={me} ctl={ctl} />
      <PasswordForm id={pwd} ctl={ctl} />
    </div>
  )
}

function CurrentAccount({ id, ctl }: { id: string; ctl: GroupCtl }) {
  const ctx = useSyncExternalStore(storeContext.subscribe, storeContext.get)
  const identity = ctx.identity
  const current = identity?.stores.find((s) => s.storeId === ctx.storeId)

  return (
    <PanelGroup
      id={id}
      Icon={IconUser}
      title="当前账号"
      open={ctl.isOpen(id)}
      onToggle={() => ctl.toggle(id)}
      summary={identity?.name ? `${identity.name} · ${current ? storeLabel(current) : '未选择工作门店'}` : '未取到员工身份'}
    >
      <div className="flex items-center gap-3">
        <span className="grid h-11 w-11 place-items-center rounded-full bg-blue-soft text-blue">
          <IconUser className="h-5 w-5" />
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-black text-ink">{identity?.name ?? '未取到姓名'}</p>
          <p className="truncate text-[11px] text-muted">{current ? `${storeLabel(current)} · ${current.roles.join('/') || '员工'}` : '未选择工作门店'}</p>
        </div>
      </div>
      {identity && identity.stores.length > 1 && (
        <p className="mt-3 text-[11px] leading-relaxed text-muted">
          你归属 {identity.stores.length} 家门店，切换门店请用顶部的门店选择器；角色与在班数据随门店变化。
        </p>
      )}
    </PanelGroup>
  )
}

function PasswordForm({ id, ctl }: { id: string; ctl: GroupCtl }) {
  const { push } = useToast()
  const navigate = useNavigate()
  const [oldPwd, setOldPwd] = useState('')
  const [newPwd, setNewPwd] = useState('')
  const [confirm, setConfirm] = useState('')
  const [busy, setBusy] = useState(false)
  const dirty = oldPwd !== '' || newPwd !== '' || confirm !== ''

  useEffect(() => {
    ctl.setDirty(id, dirty)
  }, [ctl, id, dirty])

  const submit = async () => {
    if (!oldPwd) {
      push('请输入原密码', 'error')
      return
    }
    const problem = passwordProblem(newPwd)
    if (problem) {
      push(problem, 'error')
      return
    }
    if (newPwd !== confirm) {
      push('两次输入的新密码不一致', 'error')
      return
    }
    setBusy(true)
    try {
      await authApi.changePassword(oldPwd, newPwd)
      push('密码已修改，请用新密码重新登录', 'success')
      // 后端改密已清掉全部 refresh 白名单，本地会话不可再续期；顺手把当前 access 拉黑，
      // 否则它会一直有效到 access TTL（2h），"请重新登录"就成了空话。与顶部登出同一套收敛动作。
      session.bump()
      try {
        await authApi.logout()
      } catch {
        // 登出失败也要清本地会话
      }
      tokenStore.clear()
      storeContext.reset()
      navigate('/login', { replace: true })
    } catch (err) {
      const code = err instanceof BizError ? err.code : 0
      push(code === BizCode.PASSWORD_INCORRECT ? '原密码不正确' : err instanceof BizError ? err.message : '修改失败，请稍后重试', 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <PanelGroup
      id={id}
      Icon={IconLock}
      title="修改密码"
      open={ctl.isOpen(id)}
      dirty={dirty}
      onToggle={() => ctl.toggle(id)}
      summary="8-20 位、含字母与数字；改密后需重新登录"
    >
      <div className="flex max-w-[520px] flex-col gap-3">
        <Labeled label="原密码" required>
          <input type="password" className={INPUT} value={oldPwd} onChange={(e) => setOldPwd(e.target.value)} autoComplete="current-password" />
        </Labeled>
        <Labeled label="新密码（8-20 位，含字母与数字，不含空白）" required>
          <input type="password" className={INPUT} value={newPwd} onChange={(e) => setNewPwd(e.target.value)} autoComplete="new-password" />
        </Labeled>
        <Labeled label="确认新密码" required>
          <input type="password" className={INPUT} value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="new-password" />
        </Labeled>
        <button type="button" disabled={busy} onClick={() => void submit()} className={cx('flex h-10 items-center justify-center gap-2 rounded-xl text-sm font-bold transition disabled:opacity-60', NAVY)}>
          <IconLock className="h-4 w-4" /> {busy ? '提交中…' : '确认修改'}
        </button>
        <p className="text-[11px] leading-relaxed text-muted">
          改密后当前会话即失效，需用新密码重新登录（后端会清掉全部 refresh 令牌）。
          <span className="font-semibold text-ink">记不住原密码时本页没有出口</span>——后端的短信自助重置端点早已上线
          （<code className="font-mono text-[10px]">/auth/password/reset</code>，scene=RESET），但 B 端登录页尚未提供该入口，
          当前只能请平台管理员重置。
        </p>
      </div>
    </PanelGroup>
  )
}
