/** 品牌标志：深海军蓝圆角块 + 暖金黄线性爪印（不使用任何真实品牌 logo）。 */
export default function Logo({ className = 'h-11 w-11' }: { className?: string }) {
  return (
    <svg viewBox="0 0 48 48" className={className} role="img" aria-label="爪印 PetCare 标志">
      <rect x="1.5" y="1.5" width="45" height="45" rx="14" fill="#12355B" />
      <rect x="1.5" y="1.5" width="45" height="45" rx="14" fill="url(#logoSheen)" opacity="0.25" />
      <g fill="#F7C948">
        <ellipse cx="24" cy="30" rx="7.6" ry="6.4" />
        <ellipse cx="14.2" cy="20.6" rx="3.3" ry="4.2" />
        <ellipse cx="20.6" cy="15.7" rx="3.15" ry="4.3" />
        <ellipse cx="27.4" cy="15.7" rx="3.15" ry="4.3" />
        <ellipse cx="33.8" cy="20.6" rx="3.3" ry="4.2" />
      </g>
      <defs>
        <linearGradient id="logoSheen" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stopColor="#ffffff" stopOpacity="0.5" />
          <stop offset="100%" stopColor="#ffffff" stopOpacity="0" />
        </linearGradient>
      </defs>
    </svg>
  )
}
