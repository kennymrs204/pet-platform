import { useNavigate } from 'react-router-dom'
import { useRegister } from '../../auth/useRegister'
import { useToast } from '../ui/Toast'
import PetBackdrop from '../decorative/PetBackdrop'
import BrandPanel from '../login/BrandPanel'
import MobileBrand from '../login/MobileBrand'
import RegisterCard from './RegisterCard'

/** 注册页：与登录页同款布局（左品牌右卡片），成功后回 /login。 */
export default function RegisterPage() {
  const navigate = useNavigate()
  const toast = useToast()

  const vm = useRegister({
    onRegistered: () => navigate('/login', { replace: true }),
    onToast: (msg, tone) => toast.push(msg, tone),
  })

  return (
    <main className="relative flex min-h-[100dvh] items-center justify-center overflow-hidden bg-brand-gradient">
      <PetBackdrop />
      <div className="relative z-10 mx-auto grid w-full max-w-6xl grid-cols-1 items-center gap-10 px-5 py-10 sm:px-8 lg:grid-cols-2 lg:gap-16 lg:py-14">
        <BrandPanel />
        <div className="flex flex-col gap-8">
          <MobileBrand />
          <RegisterCard vm={vm} />
        </div>
      </div>
    </main>
  )
}
