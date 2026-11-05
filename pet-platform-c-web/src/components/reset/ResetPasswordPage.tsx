import { useNavigate } from 'react-router-dom'
import { useResetPassword } from '../../auth/useResetPassword'
import { session } from '../../auth/session'
import { tokenStore } from '../../auth/tokenStore'
import { useToast } from '../ui/Toast'
import PetBackdrop from '../decorative/PetBackdrop'
import BrandPanel from '../login/BrandPanel'
import MobileBrand from '../login/MobileBrand'
import ResetPasswordCard from './ResetPasswordCard'

/** 忘记密码（ADR-011）：未登录即可访问；成功后不签发 token，清本地旧会话并回登录页。 */
export default function ResetPasswordPage() {
  const navigate = useNavigate()
  const toast = useToast()

  const vm = useResetPassword({
    onReset: () => {
      // 后端已清空 refresh 白名单且不会签发新 token；本地旧 access/refresh 一并清掉。
      session.bump()
      tokenStore.clear()
      toast.push('密码已重置，请使用新密码登录', 'success')
      navigate('/login', { replace: true })
    },
    onToast: (msg, tone) => toast.push(msg, tone),
  })

  return (
    <main className="relative flex min-h-[100dvh] items-center justify-center overflow-hidden bg-brand-gradient">
      <PetBackdrop />
      <div className="relative z-10 mx-auto grid w-full max-w-6xl grid-cols-1 items-center gap-10 px-5 py-10 sm:px-8 lg:grid-cols-2 lg:gap-16 lg:py-14">
        <BrandPanel />
        <div className="flex flex-col gap-8">
          <MobileBrand />
          <ResetPasswordCard vm={vm} />
        </div>
      </div>
    </main>
  )
}
