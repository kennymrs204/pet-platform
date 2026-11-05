import Logo from '../ui/Logo'

/** 移动端顶部紧凑品牌条（lg 以上隐藏，桌面由 BrandPanel 承担品牌展示）。 */
export default function MobileBrand() {
  return (
    <header className="flex flex-col items-center text-center lg:hidden">
      <Logo className="h-12 w-12 shadow-soft" />
      <p className="mt-3 text-xl font-bold tracking-tight text-navy">
        爪印 <span className="text-gold-dark">PetCare</span>
      </p>
      <p className="mt-1 text-[13px] text-muted">让每一次托付，都安心可见</p>
    </header>
  )
}
