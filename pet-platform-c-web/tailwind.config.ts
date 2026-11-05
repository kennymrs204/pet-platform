import type { Config } from 'tailwindcss'

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        gold: { DEFAULT: '#F7C948', light: '#FFD75A', dark: '#B98A16' },
        ivory: '#FFF9E8',
        warmgray: '#F5F3EE',
        navy: { DEFAULT: '#12355B', soft: '#1E4B8F', deep: '#0C2740' },
        ink: '#172B4D',
        muted: '#6B7280',
      },
      borderRadius: {
        xl: '16px',
        '2xl': '20px',
        '3xl': '24px',
        '4xl': '28px',
      },
      boxShadow: {
        soft: '0 8px 30px -12px rgba(18,53,91,0.20)',
        card: '0 24px 60px -24px rgba(18,53,91,0.35)',
        btn: '0 10px 24px -10px rgba(18,53,91,0.55)',
      },
      fontFamily: {
        sans: ['system-ui', '-apple-system', 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', 'sans-serif'],
      },
      backgroundImage: {
        'brand-gradient':
          'linear-gradient(155deg, #FFFCF2 0%, #FFF9E8 42%, #FBF3E2 72%, #F5F3EE 100%)',
        'btn-gradient': 'linear-gradient(135deg, #1E4B8F 0%, #12355B 55%, #2A2F7A 100%)',
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
        'fade-up': 'fade-up 0.55s cubic-bezier(0.22,1,0.36,1) both',
        'toast-in': 'toast-in 0.28s cubic-bezier(0.22,1,0.36,1) both',
      },
    },
  },
  plugins: [],
} satisfies Config
