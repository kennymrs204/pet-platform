import type { SVGProps } from 'react'

/** B 端线性小图标（表单前缀/操作），24x24 stroke 风格。 */
type P = SVGProps<SVGSVGElement>

function Svg({ children, ...p }: P) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      {...p}
    >
      {children}
    </svg>
  )
}

export const IconUser = (p: P) => (
  <Svg {...p}>
    <circle cx="12" cy="8" r="3.8" />
    <path d="M4.5 20.5a7.5 7.5 0 0 1 15 0" />
  </Svg>
)

export const IconLock = (p: P) => (
  <Svg {...p}>
    <rect x="5" y="10.5" width="14" height="10" rx="2.5" />
    <path d="M8 10.5V8a4 4 0 0 1 8 0v2.5" />
  </Svg>
)

export const IconMsg = (p: P) => (
  <Svg {...p}>
    <path d="M20 12.5a7.5 7.5 0 0 1-7.5 7.5H4.5l1.4-3.2a7.5 7.5 0 1 1 14.1-4.3Z" />
    <path d="M8.5 11h.01M12 11h.01M15.5 11h.01" />
  </Svg>
)

export const IconEye = (p: P) => (
  <Svg {...p}>
    <path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z" />
    <circle cx="12" cy="12" r="3" />
  </Svg>
)

export const IconEyeOff = (p: P) => (
  <Svg {...p}>
    <path d="M4 4l16 16" />
    <path d="M9.9 5.9A9.5 9.5 0 0 1 12 5.5c6 0 9.5 6.5 9.5 6.5a16 16 0 0 1-2.9 3.7M6.1 8.2A15.6 15.6 0 0 0 2.5 12S6 18.5 12 18.5a9.7 9.7 0 0 0 3.2-.5" />
    <path d="M9.9 10.5a3 3 0 0 0 4 4.2" />
  </Svg>
)

export const IconShield = (p: P) => (
  <Svg {...p}>
    <path d="M12 3 5 5.8v5c0 4.4 3 8.4 7 9.4 4-1 7-5 7-9.4v-5Z" />
    <path d="m9.2 11.8 2 2 3.6-3.8" />
  </Svg>
)

export const IconLogout = (p: P) => (
  <Svg {...p}>
    <path d="M9.5 21H5.5A1.5 1.5 0 0 1 4 19.5v-15A1.5 1.5 0 0 1 5.5 3h4" />
    <path d="m15.5 16.5 4.5-4.5-4.5-4.5M20 12H9.5" />
  </Svg>
)

export const IconChat = (p: P) => (
  <Svg {...p}>
    <circle cx="9" cy="10" r="1.1" fill="currentColor" stroke="none" />
    <circle cx="15" cy="10" r="1.1" fill="currentColor" stroke="none" />
    <path d="M12 3.5a8.5 8.5 0 0 1 8.5 8.5c0 4.7-3.8 8.5-8.5 8.5-.9 0-1.8-.1-2.6-.4l-4.4 1.4 1.5-3.7a8.4 8.4 0 0 1-3-6.3A8.5 8.5 0 0 1 12 3.5Z" />
  </Svg>
)

/* ---- 左品牌栏功能图标（对齐设计稿图二的扁平彩色圆风格） ---- */

export const IconHouse = (p: P) => (
  <Svg {...p}>
    <path d="m3 10.5 9-7.5 9 7.5V20a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 20Z" />
    <path d="M9.5 21.5V13h5v8.5" />
  </Svg>
)

export const IconDog = (p: P) => (
  <Svg {...p}>
    <path d="M7 3.5v6a5 5 0 0 0 10 0v-6l-2.6 2h-4.8Z" />
    <path d="M7 3.5 4 5.5v4l3 1.5M17 3.5l3 2v4l-3 1.5" />
    <path d="M10.5 11h.01M13.5 11h.01" />
    <path d="M11 14h2" />
  </Svg>
)

export const IconHeart = (p: P) => (
  <Svg {...p}>
    <path d="M12 20.5C7.2 16.4 3.5 13.2 3.5 9.3 3.5 6.7 5.5 4.8 7.8 4.8c1.7 0 3.2.9 4.2 2.4 1-1.5 2.5-2.4 4.2-2.4 2.3 0 4.3 1.9 4.3 4.5 0 3.9-3.7 7.1-8.5 11.2Z" />
  </Svg>
)

export const IconBath = (p: P) => (
  <Svg {...p}>
    <path d="M4 12.5h16V15a4.5 4.5 0 0 1-4.5 4.5h-7A4.5 4.5 0 0 1 4 15Z" />
    <path d="M7 12.5V6a2 2 0 0 1 3.5-1.3" />
    <path d="M9.5 6h.01M2.5 21l1-1.5M21.5 21l-1-1.5" />
  </Svg>
)

/* ---- 工作台（B-HomePage.png）所需图标 ---- */

export const IconBell = (p: P) => (
  <Svg {...p}>
    <path d="M6 9.5a6 6 0 0 1 12 0c0 4 1.5 5.5 1.5 5.5h-15S6 13.5 6 9.5Z" />
    <path d="M10 18.5a2 2 0 0 0 4 0" />
  </Svg>
)

export const IconPaw = (p: P) => (
  <Svg {...p}>
    <ellipse cx="12" cy="15.5" rx="4.6" ry="3.9" />
    <ellipse cx="5.8" cy="10.2" rx="2.1" ry="2.7" />
    <ellipse cx="10" cy="7.2" rx="2" ry="2.8" />
    <ellipse cx="14" cy="7.2" rx="2" ry="2.8" />
    <ellipse cx="18.2" cy="10.2" rx="2.1" ry="2.7" />
  </Svg>
)

export const IconClipboard = (p: P) => (
  <Svg {...p}>
    <rect x="5" y="4.5" width="14" height="16.5" rx="2" />
    <path d="M9 4.5V3h6v1.5" />
    <path d="M8.5 10.5h7M8.5 14h7M8.5 17.5h4" />
  </Svg>
)

export const IconUsers = (p: P) => (
  <Svg {...p}>
    <circle cx="9" cy="8" r="3.4" />
    <path d="M2.8 20a6.2 6.2 0 0 1 12.4 0" />
    <path d="M15.5 5.2a3.4 3.4 0 0 1 0 5.6M17 14.4a6.2 6.2 0 0 1 4.2 5.6" />
  </Svg>
)

export const IconChart = (p: P) => (
  <Svg {...p}>
    <path d="M4 20V4" />
    <path d="M4 20h16" />
    <path d="M8 16.5v-5M12.5 16.5V8M17 16.5v-7" />
  </Svg>
)

export const IconGear = (p: P) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="3.2" />
    <path d="M12 2.8v2.6M12 18.6v2.6M2.8 12h2.6M18.6 12h2.6M5.5 5.5l1.8 1.8M16.7 16.7l1.8 1.8M18.5 5.5l-1.8 1.8M7.3 16.7l-1.8 1.8" />
  </Svg>
)

export const IconChevronDown = (p: P) => (
  <Svg {...p}>
    <path d="m6 9.5 6 6 6-6" />
  </Svg>
)

export const IconChevronRight = (p: P) => (
  <Svg {...p}>
    <path d="m9.5 5.5 6 6.5-6 6.5" />
  </Svg>
)

export const IconCalendar = (p: P) => (
  <Svg {...p}>
    <rect x="3.5" y="5" width="17" height="16" rx="2" />
    <path d="M3.5 10h17M8 2.8v4M16 2.8v4" />
  </Svg>
)

export const IconWallet = (p: P) => (
  <Svg {...p}>
    <rect x="3" y="6" width="18" height="14" rx="2.5" />
    <path d="M3 10.5h18" />
    <circle cx="16.5" cy="15" r="1.4" />
  </Svg>
)

export const IconBox = (p: P) => (
  <Svg {...p}>
    <path d="m12 2.8 8.5 4.4v9.6L12 21.2 3.5 16.8V7.2Z" />
    <path d="M3.5 7.2 12 11.6l8.5-4.4M12 11.6v9.6" />
  </Svg>
)

export const IconTrendUp = (p: P) => (
  <Svg {...p}>
    <path d="m3.5 17 6-6 4 4 7-8" />
    <path d="M16.5 7h4v4" />
  </Svg>
)

export const IconHeartPulse = (p: P) => (
  <Svg {...p}>
    <path d="M12 20.5C7.2 16.4 3.5 13.2 3.5 9.3 3.5 6.7 5.5 4.8 7.8 4.8c1.7 0 3.2.9 4.2 2.4 1-1.5 2.5-2.4 4.2-2.4 2.3 0 4.3 1.9 4.3 4.5 0 3.9-3.7 7.1-8.5 11.2Z" />
    <path d="M5.8 12h2.7l1.5-2.5 2 4.5 1.5-2h4" />
  </Svg>
)

export const IconStar = (p: P) => (
  <Svg {...p}>
    <path d="m12 3.2 2.7 5.4 6 .9-4.3 4.2 1 6-5.4-2.8-5.4 2.8 1-6L3.3 9.5l6-.9Z" />
  </Svg>
)

export const IconPlus = (p: P) => (
  <Svg {...p}>
    <path d="M12 5v14M5 12h14" />
  </Svg>
)

export const IconImage = (p: P) => (
  <Svg {...p}>
    <rect x="3.5" y="4.5" width="17" height="15" rx="2.5" />
    <circle cx="9" cy="9.5" r="1.6" />
    <path d="m5.2 17.6 4.2-4.2a1.7 1.7 0 0 1 2.4 0l4.1 4.1M14.8 15.2l1.4-1.4a1.7 1.7 0 0 1 2.4 0l1.2 1.2" />
  </Svg>
)

/* ---- 宠物管理页（pet-TakeCare.png）补充图标 ---- */

export const IconMore = (p: P) => (
  <Svg {...p} strokeWidth={2.4}>
    <path d="M5.5 12h.01M12 12h.01M18.5 12h.01" />
  </Svg>
)

export const IconClock = (p: P) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="8.8" />
    <path d="M12 7.5V12l3 2" />
  </Svg>
)

export const IconEdit = (p: P) => (
  <Svg {...p}>
    <path d="M4 20l1-4.2L16.6 4.2a1.8 1.8 0 0 1 2.6 0l.6.6a1.8 1.8 0 0 1 0 2.6L8.2 19 4 20Z" />
    <path d="m14.8 6 2.6 2.6" />
  </Svg>
)

export const IconCat = (p: P) => (
  <Svg {...p}>
    <path d="M4.5 11 4 4.5l4.2 3a8.6 8.6 0 0 1 7.6 0L20 4.5 19.5 11a7.8 7.8 0 0 1-2.1 9.2 8.4 8.4 0 0 1-10.8 0A7.8 7.8 0 0 1 4.5 11Z" />
    <path d="M9 12h.01M15 12h.01" />
    <path d="M12 15.5v1.2M10.4 18.6c1 .7 2.2.7 3.2 0" />
  </Svg>
)

export const IconCheckCircle = (p: P) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="8.8" />
    <path d="m8.2 12.3 2.6 2.6 5-5.4" />
  </Svg>
)

export const IconMoon = (p: P) => (
  <Svg {...p}>
    <path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5Z" />
  </Svg>
)

export const IconHelp = (p: P) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="8.8" />
    <path d="M9.6 9.4a2.5 2.5 0 0 1 4.8.8c0 1.7-2.4 2-2.4 3.6" />
    <path d="M12 16.8h.01" />
  </Svg>
)

/* ---- 托管服务页（pet-TuoGuan.png）补充图标 ---- */

export const IconSearch = (p: P) => (
  <Svg {...p}>
    <circle cx="11" cy="11" r="6.8" />
    <path d="m16 16 4.5 4.5" />
  </Svg>
)

export const IconMapPin = (p: P) => (
  <Svg {...p}>
    <path d="M12 21s7-5.8 7-11a7 7 0 1 0-14 0c0 5.2 7 11 7 11Z" />
    <circle cx="12" cy="10" r="2.6" />
  </Svg>
)

export const IconXCircle = (p: P) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="8.8" />
    <path d="m9.2 9.2 5.6 5.6M14.8 9.2l-5.6 5.6" />
  </Svg>
)

export const IconBulb = (p: P) => (
  <Svg {...p}>
    <path d="M9 17.5h6M10 20.5h4" />
    <path d="M9.2 17.5C7 16 5.6 13.7 5.6 11a6.4 6.4 0 1 1 12.8 0c0 2.7-1.4 5-3.6 6.5" />
  </Svg>
)
