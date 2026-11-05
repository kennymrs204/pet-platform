import { useNavigate } from 'react-router-dom'
import { useLogin } from '../../auth/useLogin'
import { useToast } from '../ui/Toast'
import PetBackdrop from '../decorative/PetBackdrop'
import BrandPanel from './BrandPanel'
import LoginCard from './LoginCard'
import MobileBrand from './MobileBrand'

/** 登录页：桌面左品牌右卡片，移动端隐藏大插画仅紧凑品牌条。 */
export default function LoginPage() {
  const navigate = useNavigate()
  const toast = useToast()

  const vm = useLogin({
    onLoggedIn: () => navigate('/home', { replace: true }),
    onToast: (msg, tone) => toast.push(msg, tone),
  })

  return (
    <main className="relative flex min-h-[100dvh] items-center justify-center overflow-hidden bg-brand-gradient">
      <PetBackdrop />
      <div className="relative z-10 mx-auto grid w-full max-w-6xl grid-cols-1 items-center gap-10 px-5 py-10 sm:px-8 lg:grid-cols-2 lg:gap-16 lg:py-14">
        <BrandPanel />
        <div className="flex flex-col gap-8">
          <MobileBrand />
          <LoginCard vm={vm} />
        </div>
      </div>
    </main>
  )
}
