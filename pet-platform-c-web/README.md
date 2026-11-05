# pet-platform-c-web（C 端前端）

宠物服务与托管平台「爪印 PetCare」C 端 Web 应用。B 端为独立应用（`pet-platform-b-web`，5174），两端不共享登录与页面。

## 技术栈

Vite 5 + React 18 + TypeScript 5（strict）+ Tailwind CSS 3 + react-router-dom 6 + lucide-react。

## 快速开始

```bash
npm install
npm run dev        # 默认 http://localhost:5173（端口被占用时 Vite 自动顺延）
```

```bash
npm run typecheck  # tsc --noEmit
npm run build      # 产物 dist/
npm run preview    # 预览构建产物
```

## 两种数据模式（契约完全一致）

| 模式 | 配置 | 说明 |
|---|---|---|
| mock | `.env.development` 中 `VITE_USE_MOCK=true` | 无需后端；验证码打印在浏览器 Console（`[MOCK-SMS]`） |
| 真实后端 | `VITE_USE_MOCK=false`（当前仓库缺省） | 经 Vite 代理 `/api → http://localhost:8080`（见 `vite.config.ts`） |

开发代理目标可用 `VITE_PROXY_TARGET` 覆盖。

## 后端接口契约

- `POST /api/v1/public/sms-code`，body `{phone, scene?}` —— 60s/次、日上限 10
- `POST /api/v1/auth/register`，body `{phone,code,password}` —— 验证码注册
- `POST /api/v1/auth/login`，body `{phone,code}` —— 验证码登录
- `POST /api/v1/auth/login/password`，body `{phone,password}` —— 密码登录
- `POST /api/v1/auth/refresh`，body `{refreshToken}` —— 刷新即轮换
- `POST /api/v1/auth/logout` —— 需 `Authorization: Bearer <accessToken>`
- 统一信封 `{code,message,data,traceId}`，仅 `code===0` 成功；业务失败也是 HTTP 200 + 非零 code
- 开发默认账号（local / docker profile）：`13800000000` / `Pet@123456`（启动时自动建档，可直接登录）

> 短信验证码当前为开发模式：码写入后端日志 `[DEV-SMS]`。接入正式短信网关前，不要对公网开放注册。

## 目录结构

```
src/
├── api/          # 信封/错误码 + fetch 封装/会话续期 + 各域端点 + mock
├── auth/         # tokenStore(sessionStorage+内存副本) + session(refresh 状态机) + useLogin/useRegister
├── components/   # ui / layout / login / register / booking / order / pet / decorative
└── pages/        # 首页、预约、订单与详情、宠物与档案、托管与详情、消息中心、我的
```

## 安全说明

- token 存 sessionStorage（关标签页即清）+ 内存副本；`tokenStore` 已做抽象，生产可替换为 BFF httpOnly cookie。
- 协议/隐私政策链接为占位（无后端页面，不跳转）。

## 部署

`npm run build` 后将 `dist/` 交给静态托管，并把 `/api` 反向代理到后端 `8080`；生产环境按实际地址配置 `.env.production` 的 `VITE_API_BASE`，且 `VITE_USE_MOCK=false`。
