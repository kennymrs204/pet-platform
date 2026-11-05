# 宠物服务/托管平台（pet-platform）

B2C 宠物服务与托管平台后端。Java 21 + Spring Boot 3.5 的**模块化单体**：15 个业务域（每个拆 `-api` 契约 / `-service` 实现）+ `common` 共享内核 + `app` 装配根，共 32 个 Maven 模块，最终产出唯一可执行 jar。

配套前端为同级的两个独立应用：

| 目录 | 说明 | 默认端口 |
|---|---|---|
| `pet-platform-b-web` | B 端（门店员工 / 平台管理员） | 5174 |
| `pet-platform-c-web` | C 端（宠物主人） | 5173 |

## 技术栈

- Java 21（`--release 21`）、Spring Boot 3.5.16
- MyBatis-Plus 3.5.17、MySQL 8、Flyway（启动自动迁移）
- Redis 7（Redisson 复用 Spring Data Redis 连接配置）
- JWT HS256（jjwt 0.13）
- MinIO 8（对象存储，浏览器直传 + presigned URL 读取）
- springdoc 2.8 + Knife4j 静态 UI

## 模块结构

```
pet-platform-common            共享内核（Result/分页/错误码/登录主体/存储抽象）
pet-platform-<domain>-api      纯契约：DTO + 跨模块接口，不依赖实现与框架
pet-platform-<domain>-service  实现：domain / mapper / service / web
pet-platform-app               装配根：启动类、安全、配置、定时任务、事件监听
```

业务域：`user`、`pet`、`store`、`employee`、`catalog`、`schedule`、`appointment`、`order`、`payment`、`fulfillment`、`boarding`、`notification`、`message`。`medical`、`recruitment` 为预留模块（当前无实现）。

### 架构约定

- `-api` 包只放契约（DTO / 接口），不依赖任何实现包与框架类型。
- 模块之间只能通过对方 `-api` 交互，禁止跨模块直接引用实现。
- 事件链单向：`payment → order → appointment`，下游不反向依赖上游。
- `app` 只做装配，不被任何模块依赖；`common` 不反向依赖业务模块。

## 环境要求

- JDK 21 及以上（目标字节码 21；JDK 23+ 的注解处理已在 `pom.xml` 中显式配置）
- Maven 3.9+
- MySQL 8、Redis 7
- MinIO（可选；不启用时设置 `PET_STORAGE_ENABLED=false`）

构建不需要数据库或 Redis。

## 构建

```bash
mvn -B clean package
# 产物：pet-platform-app/target/pet-platform-app-1.0.0-SNAPSHOT.jar
```

## 配置

所有敏感项均经环境变量注入，不写死在代码或数据库里。

| 变量 | 说明 | 默认 |
|---|---|---|
| `PET_DB_URL` | MySQL JDBC URL | `jdbc:mysql://127.0.0.1:3306/pet_platform?...` |
| `PET_DB_USER` / `PET_DB_PASSWORD` | 数据库账号 | `root` / 空 |
| `PET_REDIS_HOST` / `PET_REDIS_PORT` / `PET_REDIS_PASSWORD` / `PET_REDIS_DB` | Redis 连接 | `127.0.0.1:6379` / 空 / `0` |
| `PET_JWT_SECRET` | JWT HS256 密钥，**至少 32 字节**；未设置则启动失败 | 无（必填） |
| `PET_JWT_ACCESS_EXPIRE` | 访问令牌有效期（秒） | `7200` |
| `PET_JWT_REFRESH_EXPIRE` | 刷新令牌有效期（秒） | `1209600`（14 天） |
| `PET_PAYMENT_CHANNEL` | 支付通道：`mock` / `wechat` | `mock` |
| `PET_STORAGE_ENABLED` | 是否启用对象存储 | `true` |
| `PET_MINIO_ENDPOINT` | 服务端访问 MinIO 的地址 | 见 profile |
| `PET_STORAGE_EXTERNAL_ENDPOINT` | 签发 presigned URL 用的浏览器可达地址 | `http://localhost:9000` |
| `PET_MINIO_USER` / `PET_MINIO_PASSWORD` | MinIO 账号 | `petadmin` / `pet123456` |

> 支付通道：`mock` 为模拟通道，用于本地演示与联调；`wechat` 目前是预留桩（发起支付返回未开通错误），接入真实微信支付需实现 `PaymentGateway` 的预下单 / 查询 / 回调验签。

## 运行

```bash
export PET_JWT_SECRET='<至少32字节的随机串>'
export PET_DB_PASSWORD='<数据库密码>'
java -jar pet-platform-app/target/pet-platform-app-1.0.0-SNAPSHOT.jar
```

- 端口 `8080`
- 健康检查：`/actuator/health`
- 接口文档：`/doc.html`（Knife4j UI），OpenAPI JSON：`/v3/api-docs`（`local`/缺省 profile 默认开启；容器部署默认关闭，见下文）

### Profile

| profile | 用途 | 数据源 |
|---|---|---|
| 缺省 | 通用 | 环境变量，缺省 `127.0.0.1:3306` |
| `local` | 本机直跑，连本地容器栈 | `127.0.0.1:3307` + Redis `6379` |
| `docker` | 容器内运行（compose） | 服务名 `mysql` / `redis` |

```bash
java -jar pet-platform-app/target/pet-platform-app-1.0.0-SNAPSHOT.jar --spring.profiles.active=local
```

## Docker Compose 快速部署（推荐）

```bash
cp .env.example .env          # 必须填写 PET_JWT_SECRET（≥32 字节）

# 1) 基础设施：MySQL(3307) / Redis(6379) / MinIO(9000, 9001)
docker compose up -d

# 2) 追加后端（多阶段构建，:8080）
docker compose --profile app up -d --build
```

- 数据保存在 `mysql-data` / `redis-data` / `minio-data` 三个卷中，`docker compose down` 不删数据。
- `.env` 含密钥，**不要随代码一起外发**；交付包保留 `.env.example` 即可。
- MinIO 控制台：`http://localhost:9001`（默认仅本机可访问）。
- **端口暴露**：MySQL / Redis / MinIO 默认只绑 `127.0.0.1`，仅后端容器与本机可访问；跨机访问需设 `PET_DB_BIND` / `PET_REDIS_BIND` / `PET_MINIO_BIND` / `PET_MINIO_CONSOLE_BIND=0.0.0.0`，并同步更换强口令。
- **接口文档**：容器部署默认关闭（`/doc.html`、`/v3/api-docs` 不可用）；需要调试时设 `PET_API_DOCS_ENABLED=true`。
- **开发默认账号**：首次部署可用于初始化，完成后设 `PET_DEV_DEFAULT_ACCOUNT=disabled` 关闭。
- 生产部署请替换所有默认密码与 JWT 密钥，并按需调整端口映射。

## 数据库

Flyway 在启动时自动执行 `pet-platform-app/src/main/resources/db/migration`，当前共 41 张表：

- `V1`：核心结构 37 张表（逻辑删除列 `deleted`）
- `V2`~`V6`：基础数据（平台总部门店、8 角色、角色-权限映射、技能、服务目录、系统配置）与权限码
- `V7`~`V9`：门店代客建档、客户备注、代建宠物归属
- `V10`：B 端代客下单权限
- `V11`~`V13`：门店-客户会话与消息（含图片消息）
- `V14`：取消政策快照
- `V15`：宠物健康档案变更日志
- `V16`：遛狗 GPS 轨迹
- `V17`：退款拒绝理由与前台退款审批
- `V18`：线下收款登记 / 复核

> 迁移只含结构与基础数据（8 角色、角色-权限映射、技能、服务目录、系统配置、平台总部门店），**不含任何账号、员工或普通门店**。

## 开发默认账号与首次初始化

`local` 与 `docker` profile 会种一个开发账号：`13800000000` / `Pet@123456`，并自动创建客户档案，C 端可直接使用。
生产环境请设置 `PET_DEV_DEFAULT_ACCOUNT=disabled` 关闭。

Flyway 不创建任何员工或管理员，**B 端首次使用前必须执行一次初始化脚本**（幂等，可重复执行）：

```bash
docker exec -i pet-platform-mysql \
  mysql -uroot -p"$PET_DB_PASSWORD" pet_platform < deploy/bootstrap-admin.sql
```

脚本做三件事：

- 库中没有普通门店时，创建 `id=9111` 的「默认门店」；
- 把指定手机号（默认 `13800000000`）提升为员工；
- 绑定该门店并授予 `ADMIN` 角色。

执行后用该账号登录 B 端；管理员可代入任意 OPEN 门店，再在设置 / 员工管理中配置服务价目、资源与员工。
若使用其它手机号，先修改脚本顶部的 `@admin_phone`，并确保该账号已注册。

> 短信验证码当前为开发模式：码写入后端日志 `[DEV-SMS]`。接入正式短信网关前，不要对公网开放注册。

## 目录

```
pet-platform/
├── pom.xml                 # 父 POM（依赖与版本管理）
├── docker-compose.yml      # MySQL / Redis / MinIO / 可选后端
├── docker/Dockerfile       # 多阶段构建
├── deploy/bootstrap-admin.sql # 首次部署：创建管理员与首家门店（手工执行）
├── .env.example            # 环境变量模板
├── pet-platform-common/    # 共享内核
├── pet-platform-*-api/     # 各业务域契约
├── pet-platform-*-service/ # 各业务域实现
└── pet-platform-app/       # 装配根（启动类 / 安全 / 配置 / 任务 / 监听器）
```
