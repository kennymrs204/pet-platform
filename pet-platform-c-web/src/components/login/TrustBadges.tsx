import type { ReactNode } from 'react'

interface Badge {
  title: string
  icon: ReactNode
}

/** 桌面端信任区：线性图标 × 3（安全验证 / 自动建档 / 会话时长）。 */
export default function TrustBadges({ className = '' }: { className?: string }) {
  const badges: Badge[] = [
    {
      title: '安全验证登录',
      icon: (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5" aria-hidden="true">
          <path d="M12 3l7 3v5.5c0 4.3-2.9 7.7-7 9.5-4.1-1.8-7-5.2-7-9.5V6l7-3z" />
          <path d="M9 12l2.2 2.2L15.4 10" />
        </svg>
      ),
    },
    {
      title: '首次登录自动建档',
      icon: (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5" aria-hidden="true">
          <circle cx="9.5" cy="8.5" r="3.2" />
          <path d="M3.8 19.2c.6-3.1 3-5.2 5.7-5.2 1.6 0 3 .7 4 1.8" />
          <path d="M17.4 12.8v5.6M14.6 15.6h5.6" />
        </svg>
      ),
    },
    {
      title: '2 小时安全会话',
      icon: (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5" aria-hidden="true">
          <circle cx="12" cy="12" r="8.6" />
          <path d="M12 7.4V12l3 2" />
        </svg>
      ),
    },
  ]

  return (
    <ul className={className}>
      {badges.map((b) => (
        <li key={b.title} className="flex items-center gap-3 text-[13px] text-navy/85">
          <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-white/70 text-navy shadow-soft ring-1 ring-navy/8">
            {b.icon}
          </span>
          {b.title}
        </li>
      ))}
    </ul>
  )
}
