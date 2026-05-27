## Why

scaffold-monorepo 已经把 `JwtAuthenticationFilter` / `JwtAuthenticationEntryPoint` / `SecurityConfig` 留成了空壳：token 不解析、没有 `UserDetailsService`、没有 `PasswordEncoder`、没有任何账号实体。整个 MVP（客户管理、线索管理、Dashboard 等）都需要"登录后的用户身份 + 角色"作为前提，因此必须先把 PRD §7.1 的账号与登录能力闭环——否则后续任何 capability 都无法落地 API 鉴权。

## What Changes

- 新增账号实体（`account` 表）：邮箱 / BCrypt 密码哈希 / 姓名 / 角色（ADMIN/SALES） / 状态（ENABLED/DISABLED） / 时间戳。
- 新增登录 API（`POST /api/auth/login`）：邮箱 + 密码校验通过后签发 JWT；停用账号 / 错密码统一返回 401 UNAUTHORIZED + `message` 区分子原因。
- 新增"当前用户改自己密码"API（`POST /api/auth/change-password`）：要求 `oldPassword + newPassword`，旧密码错返回 401 UNAUTHORIZED。
- 新增"当前用户信息"API（`GET /api/auth/me`）：返回当前 JWT 对应的账号脱敏信息（邮箱、姓名、角色），供前端工作台显示。
- 新增 Admin 账号管理 API：`POST /api/admin/accounts` 创建 Sales、`GET /api/admin/accounts` 列出全部账号、`PATCH /api/admin/accounts/{id}/status` 启用 / 停用。
- 填实 scaffold 留下的 `JwtAuthenticationFilter`：每次请求解 token → 实时查 `account.status`，停用即 401（满足"停用后下一次 API 请求立即登出"语义）。
- 注入 `BCryptPasswordEncoder` bean、`UserDetailsService` bean、`AuthenticationManager` 配置；JWT 使用 HS256 + 16h 有效期，secret 由部署配置注入。
- 启动时执行初始 Admin 注入：按部署配置的邮箱 + 密码（BCrypt 哈希后写库）幂等创建；存在即跳过。
- 引入 `SystemLogPort` 接口 + `Slf4jSystemLogPort` NoOp 实现：账号启用 / 停用 / 创建时调用记录；真实 JDBC 实现延后到 `progress-log` change。
- `SecurityConfig` 收紧：`POST /api/auth/login` 放行；`POST /api/admin/**` 仅 ADMIN；其余路径仍 `authenticated()`。

## Capabilities

### New Capabilities

- `auth-account`: 账号实体、登录鉴权（JWT 签发与解析）、当前用户改密、Admin 账号 CRUD、启用 / 停用语义、初始 Admin 注入、系统日志端口。

### Modified Capabilities

- `platform-foundation`: 不修改 requirements 本身；仅在 design 层填充 SecurityConfig stub 与 JwtAuthenticationFilter token 解析实现（spec 行为契约面无变化，故不写 delta spec）。

## Impact

- **新增后端代码**：`com.dealtrace.account.*`（实体 / Repository / Service / Controller / DTO）、`com.dealtrace.auth.*`（JwtService / LoginController / ChangePasswordController / MeController）、`com.dealtrace.security.*` 补全（UserDetailsService 实现 / PasswordEncoder bean / AuthenticationManager 配置 / JwtAuthenticationFilter token 解析逻辑）、`com.dealtrace.systemlog.*`（SystemLogPort 接口 + Slf4jSystemLogPort 实现）、初始 Admin 注入监听器。
- **新增 DB migration**：`V2__account.sql`（建表 + 唯一索引）。
- **新增配置**：`application.yml` 引入 `dealtrace.jwt.secret` / `dealtrace.jwt.ttl-hours` / `dealtrace.bootstrap.admin-email` / `dealtrace.bootstrap.admin-password`，全部由环境变量驱动。
- **下游 capability 依赖**：customer-management / lead-management / dashboard 等所有需要"操作者身份"或"角色权限"的 capability 都将基于本 change 的 `Authentication` 主体（`AccountPrincipal`，含 id / email / role）。
- **跨 capability follow-up**：`SystemLogPort` 进入 `progress-log` change 时必须替换为 `JdbcSystemLogPort`（已存入项目记忆 `systemlog-port-noop`）。
- **前端**：本 change 不实现前端登录页 UI（属于 frontend-workbench 范畴），仅落 API 契约；前端 axios 拦截器对 401 的处理（清 token + 跳登录）延后到 frontend-workbench change。
- **不在本 change 范围**：Sales 自助找回密码、密码强度规则、多 Admin 支持、token 黑名单 / 刷新、停用 Sales 名下线索回收逻辑（lead-management 范畴）、权限注解（@PreAuthorize 等粒度由 permission 在更细粒度时再行细化；本 change 仅做 path-level 与"是否当前用户"两种粗粒度）。
