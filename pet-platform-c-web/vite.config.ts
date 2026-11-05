import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// C 端前端 dev server：/api 代理到后端 :8080（前后端分离，开发期免跨域）。
// 端口 5173 若被占用，Vite 自动顺延（未设 strictPort）——遵循“端口冲突改本项目、不停他人进程”。
// VITE_PROXY_TARGET 可改后端地址（与 b-web 同一约定）：验收并行 :verify 实例时必需，
// 否则会出现"页面能正常登录、新端点却全 10004"——那 10004 是老容器给的 404，不是路由写错。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    host: true,
    proxy: {
      '/api': { target: process.env.VITE_PROXY_TARGET || 'http://localhost:8080', changeOrigin: true },
    },
  },
})
