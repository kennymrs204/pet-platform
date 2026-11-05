import Logo from '../ui/Logo'
import TrustBadges from './TrustBadges'

/** 桌面端左侧品牌区：品牌标识 + 标语 + 线稿场景 + 信任徽章。移动端隐藏（lg:flex）。 */
export default function BrandPanel() {
  return (
    <section className="relative hidden flex-col justify-between py-12 pr-10 lg:flex" aria-label="品牌介绍">
      <header>
        <div className="flex items-center gap-3.5">
          <Logo className="h-14 w-14 shadow-card" />
          <div>
            <p className="text-2xl font-bold tracking-tight text-navy">
              爪印 <span className="text-gold-dark">PetCare</span>
            </p>
            <p className="mt-0.5 text-[13px] text-muted">宠物服务与托管平台</p>
          </div>
        </div>
        <h1 className="mt-8 text-[26px] font-semibold leading-snug text-ink">
          让每一次托付，
          <br />
          都<span className="bg-gradient-to-r from-gold to-gold-light bg-clip-text text-transparent">安心可见</span>。
        </h1>
      </header>

      {/* 中部线稿场景：小屋 + 宠物 + 爪印 + 球 + 金色微光 */}
      <div className="relative my-10 flex items-end justify-center" aria-hidden="true">
        <svg viewBox="0 0 420 200" className="w-full max-w-md" fill="none">
          <g stroke="#12355B" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" opacity="0.55">
            {/* 宠物小屋 */}
            <path d="M40 118l60-46 60 46v62H40v-62z" />
            <path d="M32 118l68-52 68 52" />
            <path d="M78 180v-30a22 22 0 0 1 44 0v30" />
            {/* 地面线 */}
            <path d="M16 182h388" opacity="0.5" />
            {/* 小狗（坐姿线稿） */}
            <path d="M238 182c0-22 8-38 24-44 4-14 14-20 26-20s22 6 26 20c16 6 24 22 24 44" />
            <path d="M268 118c-2-12-8-18-14-16-6 2-6 12-2 20M308 118c2-12 8-18 14-16 6 2 6 12 2 20" />
            <path d="M282 138h12M288 138v8" />
            <circle cx="276" cy="128" r="1.8" fill="#12355B" />
            <circle cx="300" cy="128" r="1.8" fill="#12355B" />
            {/* 牵引绳弧线到球 */}
            <path d="M340 170c24-6 40-18 50-34" strokeDasharray="5 7" />
            <circle cx="392" cy="164" r="16" />
            <path d="M380 152c8 6 14 14 16 22M404 152c-8 6-14 14-16 22" strokeWidth="1.8" />
          </g>
          {/* 金色爪印点缀 */}
          <g fill="#F7C948" opacity="0.75">
            <g transform="translate(168,150) rotate(-14) scale(0.22)">
              <ellipse cx="60" cy="78" rx="26" ry="21" /><ellipse cx="28" cy="48" rx="10.5" ry="13.5" /><ellipse cx="49" cy="32" rx="10" ry="14" /><ellipse cx="71" cy="32" rx="10" ry="14" /><ellipse cx="92" cy="48" rx="10.5" ry="13.5" />
            </g>
            <g transform="translate(196,166) rotate(4) scale(0.18)">
              <ellipse cx="60" cy="78" rx="26" ry="21" /><ellipse cx="28" cy="48" rx="10.5" ry="13.5" /><ellipse cx="49" cy="32" rx="10" ry="14" /><ellipse cx="71" cy="32" rx="10" ry="14" /><ellipse cx="92" cy="48" rx="10.5" ry="13.5" />
            </g>
          </g>
          {/* 金色 spark */}
          <g stroke="#F7C948" strokeWidth="2" strokeLinecap="round" opacity="0.8">
            <path d="M120 56v10M115 61h10" />
            <path d="M352 74v8M348 78h8" />
            <path d="M250 52l1.6 4.4L256 58l-4.4 1.6L250 64l-1.6-4.4L244 58l4.4-1.6z" fill="#F7C948" stroke="none" />
          </g>
        </svg>
      </div>

      <footer>
        <TrustBadges className="flex flex-col gap-3.5" />
      </footer>
    </section>
  )
}
