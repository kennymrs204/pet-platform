import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// B 端 dev server：/api 代理到后端。端口 5174（C 端占 5173），冲突时 Vite 自动顺延。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    host: true,
    proxy: {
      // 默认打 :8080（共享部署栈）。临时换后端仍可用环境变量：VITE_PROXY_TARGET=http://localhost:PORT npm run dev
      '/api': {
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
