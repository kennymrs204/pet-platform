import type { Config } from 'tailwindcss'

/**
 * B 端视觉：蓝白专业风（对齐设计稿 B-login.png）——主色亮蓝、浅蓝底、白卡片。
 * 与 C 端（奶油黄+海军蓝、大圆角亲和风）刻意区分。
 */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        blue: {
          DEFAULT: '#2E6BFF',
          deep: '#1E4FD8',
          soft: '#EAF1FF',
          mist: '#F4F8FF',
        },
        ink: '#1F2937',
        muted: '#6B7280',
        line: '#E5EAF3',
      },
      borderRadius: {
        xl: '12px',
        '2xl': '16px',
        '3xl': '20px',
      },
      boxShadow: {
        card: '0 18px 50px -20px rgba(46,107,255,0.22)',
        soft: '0 6px 24px -10px rgba(31,41,55,0.14)',
      },
      fontFamily: {
        sans: ['system-ui', '-apple-system', 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', 'sans-serif'],
      },
      backgroundImage: {
        'brand-panel': 'linear-gradient(165deg, #F4F8FF 0%, #EAF1FF 55%, #DDE9FF 100%)',
      },
      keyframes: {
        'fade-up': {
          '0%': { opacity: '0', transform: 'translateY(10px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        'toast-in': {
          '0%': { opacity: '0', transform: 'translateY(-14px) scale(0.97)' },
          '100%': { opacity: '1', transform: 'translateY(0) scale(1)' },
        },
      },
      animation: {
        'fade-up': 'fade-up 0.5s cubic-bezier(0.22,1,0.36,1) both',
        'toast-in': 'toast-in 0.28s cubic-bezier(0.22,1,0.36,1) both',
      },
    },
  },
} satisfies Config
