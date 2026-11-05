/**
 * 背景装饰层：奶油黄柔光 + 极低透明度的宠物元素线稿（爪印 / 骨头 / 球 / 小屋 / 牵引绳弧线）。
 * 纯装饰：pointer-events-none + aria-hidden，不遮挡表单交互。
 */
export default function PetBackdrop() {
  return (
    <div className="pointer-events-none absolute inset-0 overflow-hidden" aria-hidden="true">
      {/* 暖金 / 海军蓝径向柔光 */}
      <div className="absolute -left-24 -top-24 h-96 w-96 rounded-full bg-gold/20 blur-3xl" />
      <div className="absolute -bottom-32 -right-20 h-[26rem] w-[26rem] rounded-full bg-navy/[0.07] blur-3xl" />
      <div className="absolute right-1/4 top-1/3 h-64 w-64 rounded-full bg-gold-light/15 blur-3xl" />

      {/* 大爪印（右下） */}
      <svg viewBox="0 0 120 120" className="absolute -bottom-6 right-2 h-52 w-52 text-navy opacity-[0.05] sm:h-64 sm:w-64" fill="currentColor">
        <ellipse cx="60" cy="78" rx="26" ry="21" />
        <ellipse cx="28" cy="48" rx="10.5" ry="13.5" />
        <ellipse cx="49" cy="32" rx="10" ry="14" />
        <ellipse cx="71" cy="32" rx="10" ry="14" />
        <ellipse cx="92" cy="48" rx="10.5" ry="13.5" />
      </svg>

      {/* 小爪印串（左上，沿弧线排布） */}
      <svg viewBox="0 0 220 160" className="absolute left-4 top-10 h-36 w-52 text-navy opacity-[0.05]" fill="currentColor">
        <g transform="translate(10,110) rotate(-12) scale(0.34)">
          <ellipse cx="60" cy="78" rx="26" ry="21" /><ellipse cx="28" cy="48" rx="10.5" ry="13.5" /><ellipse cx="49" cy="32" rx="10" ry="14" /><ellipse cx="71" cy="32" rx="10" ry="14" /><ellipse cx="92" cy="48" rx="10.5" ry="13.5" />
        </g>
        <g transform="translate(75,75) rotate(6) scale(0.3)">
          <ellipse cx="60" cy="78" rx="26" ry="21" /><ellipse cx="28" cy="48" rx="10.5" ry="13.5" /><ellipse cx="49" cy="32" rx="10" ry="14" /><ellipse cx="71" cy="32" rx="10" ry="14" /><ellipse cx="92" cy="48" rx="10.5" ry="13.5" />
        </g>
        <g transform="translate(145,40) rotate(-8) scale(0.26)">
          <ellipse cx="60" cy="78" rx="26" ry="21" /><ellipse cx="28" cy="48" rx="10.5" ry="13.5" /><ellipse cx="49" cy="32" rx="10" ry="14" /><ellipse cx="71" cy="32" rx="10" ry="14" /><ellipse cx="92" cy="48" rx="10.5" ry="13.5" />
        </g>
      </svg>

      {/* 骨头（左下） */}
      <svg viewBox="0 0 100 48" className="absolute bottom-16 left-8 h-10 w-20 rotate-[-14deg] text-navy opacity-[0.055]" fill="currentColor">
        <circle cx="14" cy="13" r="10" /><circle cx="14" cy="35" r="10" />
        <circle cx="86" cy="13" r="10" /><circle cx="86" cy="35" r="10" />
        <rect x="12" y="17" width="76" height="14" rx="7" />
      </svg>

      {/* 球（右上，带弧线牵引绳） */}
      <svg viewBox="0 0 260 200" className="absolute -right-8 top-6 h-44 w-60 text-navy opacity-[0.06]" fill="none" stroke="currentColor">
        <circle cx="205" cy="52" r="24" strokeWidth="3" />
        <path d="M186 36c14 8 24 20 28 34M224 36c-14 8-24 20-28 34" strokeWidth="2.2" />
        {/* 牵引绳虚线弧 */}
        <path d="M18 178C60 120 120 96 182 74" strokeWidth="2.6" strokeDasharray="7 9" strokeLinecap="round" />
      </svg>

      {/* 宠物小屋轮廓（底部中间偏左） */}
      <svg viewBox="0 0 140 120" className="absolute bottom-6 left-1/3 hidden h-28 w-32 text-navy opacity-[0.05] md:block" fill="none" stroke="currentColor" strokeWidth="4" strokeLinejoin="round">
        <path d="M14 58L70 14l56 44" strokeLinecap="round" />
        <path d="M26 56v50h88V56" />
        <path d="M52 106V78a18 18 0 0 1 36 0v28" />
      </svg>
    </div>
  )
}
