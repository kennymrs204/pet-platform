/** 品牌标志：亮蓝圆角块 + 白色爪印（B 端配色，与 C 端 navy+gold 区分）。 */
export default function Logo({ className = 'h-9 w-9' }: { className?: string }) {
  return (
    <svg viewBox="0 0 48 48" className={className} role="img" aria-label="宠物管家标志">
      <rect x="1.5" y="1.5" width="45" height="45" rx="14" fill="#2E6BFF" />
      <g fill="#ffffff">
        <ellipse cx="24" cy="30" rx="7.6" ry="6.4" />
        <ellipse cx="14.2" cy="20.6" rx="3.3" ry="4.2" />
        <ellipse cx="20.6" cy="15.7" rx="3.15" ry="4.3" />
        <ellipse cx="27.4" cy="15.7" rx="3.15" ry="4.3" />
        <ellipse cx="33.8" cy="20.6" rx="3.3" ry="4.2" />
      </g>
    </svg>
  )
}
