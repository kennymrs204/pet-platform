# 宠物管家 B 端前端（pet-platform-b-web）

面向门店员工 / 平台管理员的 Web 桌面端，独立应用：与 C 端 `pet-platform-c-web` 分开部署，登录态、token、页面互不共享。

## 技术栈

Vite 5 + React 18 + TypeScript 5（strict）+ Tailwind CSS 3 + react-router-dom 6。无 UI 组件库，图标为内联 SVG。

## 快速开始

```bash
npm install
npm run dev        # http://localhost:5174
npm run typecheck
npm run build      # 产物 dist/
npm run preview
```

开发环境 `/api` 由 Vite 代理到 `http://localhost:8080`，可用 `VITE_PROXY_TARGET` 覆盖。

## 环境变量

| 变量 | 说明 | 默认 |
|---|---|---|
| `VITE_API_BASE` | 接口前缀 | `/api/v1` |
| `VITE_PROXY_TARGET` | 开发代理目标（仅 dev） | `http://localhost:8080` |

`.env.production` 中是域名示例，部署时按实际后端地址修改。

## 功能范围

- **登录**：密码登录 + 短信验证码登录；记住账号。
- **注册**：手机号 + 验证码 + 密码；注册后需平台管理员绑定员工身份与门店（全新库先执行后端 `deploy/bootstrap-admin.sql` 创建首个管理员）。
- **工作台**：首页、宠物管理、托管服务、遛狗服务、宠物照顾、洗澡服务、我的任务、员工管理、订单管理、客户管理、消息中心、设置，另有客户聊天页。
- 业务请求自动注入 `X-Store-Id`（门店上下文来自 `GET /auth/employee-identities`）。

## 后端契约

- 统一信封 `{code,message,data,traceId}`，仅 `code===0` 成功。
- 业务失败也是 HTTP 200 + 非零 code。
- 鉴权：`Authorization: Bearer <accessToken>`；门店端点需附 `X-Store-Id`。
- 接口文档：`http://localhost:8080/doc.html`（容器部署默认关闭，需设 `PET_API_DOCS_ENABLED=true`）。

## 目录

```
src/
├── api/         # 信封/错误码/会话续期 + 各域端点
├── auth/        # tokenStore + 会话状态机 + 登录注册
├── components/  # ui / layout / 各业务组件
├── lib/         # 日期、样式等工具
├── mock/        # 页面文案与图标等静态常量（非接口 mock）
└── pages/       # 12 个业务页 + 设置子页
```

## 部署

`npm run build` 后将 `dist/` 交给静态托管（Nginx 等），并把 `/api` 反向代理到后端 `8080`；同时按实际地址配置 `.env.production` 的 `VITE_API_BASE`。

## 已知边界

- 「统计报表」导航项为占位（后端暂无聚合端点）。
- 第三方（微信）登录入口置灰（后端无契约）。
