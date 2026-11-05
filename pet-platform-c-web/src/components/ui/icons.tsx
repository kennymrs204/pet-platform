import type { SVGProps } from 'react'

/**
 * 线性图标集（24x24 stroke 风格，与 Logo 的线性爪印一致）。
 * 全部为本项目自绘的通用图形语言，路径手工简写，不引入图标库运行时。
 */
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

export const IconMapPin = (p: P) => (
  <Svg {...p}>
    <path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z" />
    <circle cx="12" cy="10" r="3" />
  </Svg>
)

export const IconSearch = (p: P) => (
  <Svg {...p}>
    <circle cx="11" cy="11" r="7" />
    <path d="m21 21-4.3-4.3" />
  </Svg>
)

export const IconBell = (p: P) => (
  <Svg {...p}>
    <path d="M6 9a6 6 0 0 1 12 0c0 6 2 7 2 7H4s2-1 2-7" />
    <path d="M10.3 20.5a2 2 0 0 0 3.4 0" />
  </Svg>
)

/** 对话气泡（P3 门店沟通：联系门店入口/会话列表/聊天页）。 */
export const IconChat = (p: P) => (
  <Svg {...p}>
    <path d="M20 12a7.5 7.5 0 0 1-7.5 7.5c-1.2 0-2.4-.3-3.4-.8L4.5 20l1.3-4a7.5 7.5 0 1 1 14.2-4Z" />
    <path d="M8.5 12h.01M12 12h.01M15.5 12h.01" />
  </Svg>
)

export const IconPaw = (p: P) => (
  <Svg {...p} strokeWidth={0} fill="currentColor">
    <ellipse cx="12" cy="15.5" rx="4.6" ry="3.9" />
    <ellipse cx="6.8" cy="10.6" rx="2" ry="2.6" />
    <ellipse cx="10.4" cy="7.6" rx="1.9" ry="2.7" />
    <ellipse cx="13.6" cy="7.6" rx="1.9" ry="2.7" />
    <ellipse cx="17.2" cy="10.6" rx="2" ry="2.6" />
  </Svg>
)

export const IconScissors = (p: P) => (
  <Svg {...p}>
    <circle cx="6" cy="6" r="2.6" />
    <circle cx="6" cy="18" r="2.6" />
    <path d="M20 4 8.5 15.5M14.5 14.5 20 20M8.5 8.5 11 11" />
  </Svg>
)

export const IconHeart = (p: P) => (
  <Svg {...p}>
    <path d="M12 20.5C7.2 16.4 3.5 13.2 3.5 9.3 3.5 6.7 5.5 4.8 7.8 4.8c1.7 0 3.2.9 4.2 2.4 1-1.5 2.5-2.4 4.2-2.4 2.3 0 4.3 1.9 4.3 4.5 0 3.9-3.7 7.1-8.5 11.2Z" />
    <path d="M12 9v4.6M9.7 11.3h4.6" />
  </Svg>
)

export const IconHouse = (p: P) => (
  <Svg {...p}>
    <path d="m3 9.5 9-7 9 7V20a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 20Z" />
    <path d="M9.5 21.5V13h5v8.5" />
  </Svg>
)

export const IconCap = (p: P) => (
  <Svg {...p}>
    <path d="m2.5 9 9.5-4.5L21.5 9 12 13.5Z" />
    <path d="M6.5 11.2V16c0 1.4 2.5 2.6 5.5 2.6s5.5-1.2 5.5-2.6v-4.8" />
  </Svg>
)

export const IconCalendar = (p: P) => (
  <Svg {...p}>
    <rect x="3" y="4.5" width="18" height="17" rx="2.5" />
    <path d="M8 2.5v4M16 2.5v4M3 10h18" />
  </Svg>
)

export const IconClock = (p: P) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7v5.2l3.4 2" />
  </Svg>
)

export const IconGrid = (p: P) => (
  <Svg {...p}>
    <rect x="3.5" y="3.5" width="7" height="7" rx="2" />
    <rect x="13.5" y="3.5" width="7" height="7" rx="2" />
    <rect x="3.5" y="13.5" width="7" height="7" rx="2" />
    <rect x="13.5" y="13.5" width="7" height="7" rx="2" />
  </Svg>
)

export const IconList = (p: P) => (
  <Svg {...p}>
    <path d="M8.5 6h12M8.5 12h12M8.5 18h12M3.5 6h.01M3.5 12h.01M3.5 18h.01" />
  </Svg>
)

export const IconUser = (p: P) => (
  <Svg {...p}>
    <circle cx="12" cy="8" r="3.8" />
    <path d="M4.5 20.5a7.5 7.5 0 0 1 15 0" />
  </Svg>
)

export const IconChevronRight = (p: P) => (
  <Svg {...p}>
    <path d="m9.5 6 6 6-6 6" />
  </Svg>
)

export const IconLogOut = (p: P) => (
  <Svg {...p}>
    <path d="M9.5 21H5.5A1.5 1.5 0 0 1 4 19.5v-15A1.5 1.5 0 0 1 5.5 3h4" />
    <path d="m15.5 16.5 4.5-4.5-4.5-4.5M20 12H9.5" />
  </Svg>
)

export const IconPhone = (p: P) => (
  <Svg {...p}>
    <path d="M5 3.5h3.2l1.6 4-2.1 1.5a12.5 12.5 0 0 0 5.8 5.8l1.5-2.1 4 1.6V19a1.5 1.5 0 0 1-1.6 1.5C10.6 20 4 13.4 3.5 5.1A1.5 1.5 0 0 1 5 3.5Z" />
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

export const IconX = (p: P) => (
  <Svg {...p}>
    <path d="M6 6l12 12M18 6 6 18" />
  </Svg>
)

export const IconCheck = (p: P) => (
  <Svg {...p}>
    <path d="m5 12.5 4.5 4.5L19 7" />
  </Svg>
)

export const IconEdit = (p: P) => (
  <Svg {...p}>
    <path d="M4 20h4L20 8l-4-4L4 16v4Z" />
    <path d="m14.5 5.5 4 4" />
  </Svg>
)

export const IconTrash = (p: P) => (
  <Svg {...p}>
    <path d="M4.5 7h15M9 7V4.5h6V7M6.5 7l.8 13h9.4l.8-13" />
    <path d="M10.5 11v5.5M13.5 11v5.5" />
  </Svg>
)

export const IconStethoscope = (p: P) => (
  <Svg {...p}>
    <path d="M6 3.5v5a4 4 0 0 0 8 0v-5" />
    <path d="M6 3.5H4M14 3.5h2" />
    <path d="M10 16.5a5 5 0 0 0 9.5-2.2" />
    <circle cx="19.5" cy="12" r="2.2" />
  </Svg>
)
