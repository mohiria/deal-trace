## Context

后端 auth-account 已稳定，前端要消费的契约是确定的：

- `POST /api/auth/login`，请求 `{email, password}`，成功 `data = {token, email, name, role}`；失败统一 HTTP 401 + 信封 `code=UNAUTHORIZED`，`message` 区分语义（不存在 / 密码错 / 停用，但语义上对枚举做了防护）。
- `GET /api/auth/me`，需令牌，成功 `data = {id, email, name, role, status}`；令牌缺失 / 非法 / 过期 / 账号被停用 → HTTP 401 + `code=UNAUTHORIZED`。
- 角色枚举为 `ADMIN` / `SALES`。

前端现状：`api/client.ts` 已有 `ApiEnvelope` / `ApiError` / `unwrapEnvelope`，响应成功拦截器把信封 unwrap 成 `data`，错误直接 reject；`router` 路由表空；`auth` store 仅 token 占位；`App.vue` 渲染 `HelloWorld`；`main.ts` 已装 Pinia / Router / ArcoVue。测试基建：vitest（jsdom，`src/**/*.{test,spec}.ts`）+ Playwright（`tests/e2e`，baseURL `:5173`，经 dev proxy 打真后端）。`vite.config` 已把 `/api` 代理到后端。

约束（tech-arch §10 / §4.1，CLAUDE.md）：Vue Router 管路由、Pinia 管登录态与权限、Axios 统一 token 与错误、表单即时校验不替代后端、角色仅控制入口显隐、越权以后端为准、禁 Tailwind、Arco Design Vue 组件体系、原型只抽 token 不复刻。

## Goals / Non-Goals

**Goals:**

- 建立登录 / 登出 / 未登录拦截 / 会话失效清退 / 刷新恢复登录态的完整地基。
- 把 `api/client.ts` 升级为：请求注入 `Authorization: Bearer`、响应把 200-错误信封与非 2xx-错误信封统一归一为 `ApiError`、识别 `UNAUTHORIZED` 触发会话清退、其余错误码透传供调用方分支。
- 把 `auth` store 升级为真实登录态（令牌 + 当前用户脱敏信息 + 持久化 + 恢复核实）。
- 工作台外壳（侧栏导航 + 顶栏 + 内容区占位）+ 从原型抽取的设计 token，导航按角色显隐。
- 钉死前端测试边界：组件测试 MSW、E2E 真后端。

**Non-Goals:**

- 任何业务页面与业务数据交互（客户 / 线索 / 公海 / 详情 / dashboard / 用户管理 / 合同）——属后续 4 个 change。
- 修改密码 UI（端点已存在但不在 shell 范围）。
- 令牌刷新 / 续期机制（MVP 后端签发的令牌自然过期即过期；失效就清退重登）。
- 记住登录设备、多标签页登录态实时同步等增强。

## Decisions

### D1：组件测试用 MSW 拦在 axios 边界；E2E 打真后端

- **选择**：vitest 组件 / 集成测试新增 `msw` 依赖，在 axios 出口拦截 `/api/auth/*`，对登录成功 / 401 / me 成功 / me 失效等场景返回受控信封，断言渲染、跳转、store 状态、禁用态。Playwright E2E 沿用既有方式经 dev proxy 打真后端栈。
- **理由**：后端"禁 mock 用真 MySQL"约束的是后端集成测试，不约束前端；前端组件测试需要确定性的后端响应来制造严格 Red-Green，MSW 在网络边界 mock 是业界标准且不侵入业务代码。E2E 用真后端守住契约真实性。
- **备选**：① 直接 mock axios 实例——侵入性强、与真实序列化/拦截器脱节，否决；② 全部走真后端的集成测试——无法稳定构造 401/过期等边界且慢，否决。
- **风险**：MSW 与 jsdom + vitest globals 的初始化时机；mitigation：集中一个测试 setup 注册 server，`beforeAll/afterEach/afterAll` 标准生命周期。

### D2：令牌持久化用 localStorage，恢复时必向 `/auth/me` 核实

- **选择**：登录成功把 token 存 localStorage（固定 key，如 `dealtrace.token`）。应用启动 / 刷新时若本地有 token，先注入再调 `/auth/me` 核实；成功则用返回的 `{id,email,name,role,status}` 填充 store，失败（401）清 token 并导向登录。**不**仅凭本地 token 存在即认定已登录（spec 硬要求）。
- **理由**：spec 要求刷新后恢复登录态且对失效令牌清退。localStorage 跨刷新/重开持久；`/auth/me` 核实保证"账号会话期间被停用"也能在恢复路径被发现。
- **备选**：sessionStorage——重开标签页即丢登录态，与"刷新/重新打开后仍能恢复"不符，否决。内存 only——刷新即丢，否决。
- **权衡**：localStorage 的 XSS 暴露面。MVP 接受（与后端 JWT-in-header 方案一致，且无更高安全要求）；记入 Open Questions 供后续评估。

### D3：拦截器与 store/router 解耦——回调注入避免循环依赖

- **选择**：`api/client.ts` 不直接 import store / router（会形成 client ↔ store ↔ client 循环）。改为暴露一个"会话失效处理器"注册点（如 `setUnauthorizedHandler(fn)`），由 app 启动时把"清 store + 路由跳登录"的回调注入。请求拦截器从一个轻量 token 取值函数读取当前 token（同样注入，或直接读 localStorage 单一来源）。
- **理由**：保持 client 纯粹、可单测（D1 的 MSW 测试不需要起 router）；避免 Vue 生态常见的 store/axios 循环引用。
- **备选**：在拦截器里 `import { router }` + `useAuthStore()`——Pinia 在 setup 外调用需 `getActivePinia`，且引入循环，否决。

### D4：错误归一化——200-错误信封与非 2xx-错误信封都变成 ApiError

- **选择**：成功拦截器保留 `unwrapEnvelope`（处理 HTTP 200 但 `code!=SUCCESS`，抛 `ApiError`）。错误拦截器扩展：若 `error.response.data` 是信封形状，按其 `code`/`message` 构造 `ApiError`；否则构造网络/未知 `ApiError`。任一路径若 `code===UNAUTHORIZED` 先触发会话失效处理器再 reject。最终调用方只需 `catch (ApiError)` 按 `code` 分支。
- **理由**：auth 失败走 HTTP 401（错误路径），业务校验失败可能走 200-信封或 4xx，调用方不应关心来自哪条路径。统一成 `ApiError` 让"非鉴权业务错误透传、鉴权错误清退"两条 spec 行为可被精确实现与测试。
- **风险**：误把业务 401 之外的东西判成 UNAUTHORIZED 导致误清退；mitigation：严格以信封 `code===UNAUTHORIZED` 为准，不以 HTTP 401 裸状态为唯一依据（后端契约保证 401 必带该 code）。

### D5：角色驱动导航 = 声明式入口表 × store.role

- **选择**：导航入口以声明式配置（每项标注 `roles: ('ADMIN'|'SALES')[]`），渲染时按 `authStore.role` 过滤。shell 阶段业务入口多为占位路由；Admin 专属入口（如后续"用户管理""合同记录""系统日志"）标 `ADMIN`。路由守卫做两道：登录态守卫（未登录→登录页 / 已登录访问登录页→工作台）+ 角色守卫（访问越权路由按后端为准回落）。
- **理由**：声明式表让"显隐"与"路由可达"用同一份角色元数据，避免两处不一致。spec 要求显隐仅为体验、授权以后端为准——故角色守卫只做"减少无效入口"，真正拦截仍靠后端 + D3 的 401/403 回落。
- **备选**：在每个组件里散写 `v-if="isAdmin"`——分散易漏，shell 阶段就该集中，否决。

### D6：设计 token 从原型 `:root` 抽取为 CSS 变量 + Arco 主题覆盖

- **选择**：把原型 `:root` 的色板 / 圆角 / 阴影（`--brand`/`--green`/`--orange`/`--red`/`--radius` 等）抽到 `styles`（含 `arco-theme.css` 的 Arco token 覆盖），工作台外壳消费这些变量。**不**复刻原型的单屏 kitchen-sink 结构（它把 dashboard+workbench+drawer+state-lab 塞一屏，仅作视觉参考）。
- **理由**：CLAUDE.md / tech-arch §10.1 明确"抽 token 不复刻"，且禁 Tailwind，故走 Arco 主题 + CSS 变量。

## Risks / Trade-offs

- **[localStorage 存令牌的 XSS 面]** → MVP 接受，依赖无第三方脚本注入与 Arco 受控渲染；记入 Open Questions。
- **[`/auth/me` 在每次刷新增加一次往返]** → 可接受（轻量、且是 spec 要求的失效核实，不能省）。
- **[MSW 与 vitest 初始化时机不稳]** → 集中 setup + 标准生命周期钩子；CI 与本地一致。
- **[前端角色显隐与后端授权漂移]** → 设计上前端永不作为授权依据，D3/D4 保证后端拒绝必回落；显隐表与后端角色约束如不一致，最坏只是多/少一个入口，不构成越权。
- **[多标签页登录态不同步]** → MVP 不处理；一个标签登出，另一个标签在下次请求遇 401 时自然清退。

## Open Questions

- 令牌过期时长与"无感续期"是否在后续 change 引入？MVP 先做"失效即重登"。
- 是否需要把令牌从 localStorage 迁到更安全的载体（如内存 + httpOnly refresh）？属安全增强，超出 MVP。
- 登录页是否需要"部署信息 / 环境标识"展示？暂不做，待产品确认。
