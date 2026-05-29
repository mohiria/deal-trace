## 1. 依赖与测试基建

- [x] 1.1 新增 `msw` 开发依赖（`pnpm add -D msw`），不引入 Tailwind，沿用既有 Arco / Pinia / Router / Axios。
- [x] 1.2 建立 vitest 测试 setup：注册 MSW server，配置 `beforeAll(listen)` / `afterEach(resetHandlers)` / `afterAll(close)`；在 `vitest.config` 接入 setup 文件。
- [x] 1.3 编写 `/api/auth/*` 的 MSW 默认 handler 工具（登录成功、登录 401、`/auth/me` 成功、`/auth/me` 401 可切换），供各组件测试复用。
- [x] 1.4 在 `openspec/changes/frontend-shell/qa/` 用 vibe-coding-qa 模板写轻量测试设计（覆盖 spec 全部 Scenario 的分层归属：组件严格 Red-Green / E2E 场景优先）。

## 2. api/client 升级（先 Red 后实现）

- [x] 2.1 Red：测试请求拦截器为受保护请求注入 `Authorization: Bearer <token>`（无 token 时不注入）。
- [x] 2.2 Red：测试错误归一化——HTTP 200 但 `code!=SUCCESS`、以及非 2xx 且响应体为错误信封，均抛出携带 `code`/`message` 的 `ApiError`（沿用既有 `unwrapEnvelope` 行为不回归）。
- [x] 2.3 Red：测试响应 `code===UNAUTHORIZED` 时触发已注册的"会话失效处理器"；非 `UNAUTHORIZED` 业务错误**不**触发处理器、原样透传供 `code` 分支。
- [x] 2.4 实现：请求拦截器从单一 token 来源注入；错误拦截器做 D4 错误归一化与 `UNAUTHORIZED` 分流；暴露 `setUnauthorizedHandler` 注册点（D3 解耦，client 不 import store/router）。
- [x] 2.5 全部 2.x 测试转 Green。

## 3. auth store 升级（先 Red 后实现）

- [x] 3.1 Red：测试 `login(email,password)` 成功后 store 承载 `{token,email,name,role}`、token 写入 localStorage。
- [x] 3.2 Red：测试 `login` 失败（401）不写登录态、抛 `ApiError` 携带后端 `message`。
- [x] 3.3 Red：测试 `restore()`——本地有 token 时调 `/auth/me` 成功则用 `{id,email,name,role,status}` 填充 store；`/auth/me` 401 则清 token 且登录态为空（不得仅凭本地 token 认定已登录）。
- [x] 3.4 Red：测试 `logout()` 清除 token 与当前用户、清 localStorage。
- [x] 3.5 Red：测试角色派生（如 `isAdmin`）随 `role` 正确变化。
- [x] 3.6 实现 auth store（登录 / 登出 / 恢复核实 / 持久化 / 角色派生），并把"清 store + 跳登录"回调注册到 client 的 `setUnauthorizedHandler`（app 启动时注入，见 6.x bootstrap）。
- [x] 3.7 全部 3.x 测试转 Green。

## 4. 路由与守卫（先 Red 后实现）

- [x] 4.1 Red：测试登录态守卫——未登录访问受保护路由 → 重定向登录页；已登录访问登录页 → 重定向工作台。
- [x] 4.2 Red：测试角色守卫——`SALES` 访问 `ADMIN` 专属路由被回落（不渲染该路由内容）。
- [x] 4.3 实现路由表（登录页 + 工作台外壳 + 受保护占位子路由）与两道守卫；app 启动时先 `restore()` 再放行首跳（`main.ts` bootstrap）。
- [x] 4.4 全部 4.x 测试转 Green（守卫逻辑）。

## 5. 登录页（先 Red 后实现）

- [x] 5.1 Red：测试空邮箱 / 空密码即时校验阻止提交且不发请求。
- [x] 5.2 Red：测试正确凭据提交后承载令牌并跳转工作台（MSW 登录成功）。
- [x] 5.3 Red：测试 401 时展示后端 `message`、停留登录页、不附加账号枚举语义；账号停用语义如实展示。
- [x] 5.4 实现登录页（Arco Form/Input/Button，即时校验不替代后端），调用 `authStore.login`。
- [x] 5.5 全部 5.x 测试转 Green。

## 6. 工作台外壳与角色导航（先 Red 后实现）

- [x] 6.1 Red：测试导航按 `role` 显隐——`ADMIN` 可见 Admin 专属入口；`SALES` 不可见。
- [x] 6.2 Red：测试登出入口触发 `authStore.logout` 并回落登录页。
- [x] 6.3 实现工作台外壳（侧栏导航 + 顶栏 + 内容区占位），导航用声明式入口表 × `role` 过滤（D5）。
- [x] 6.4 从原型 `:root` 抽取设计 token 到 `styles` / `arco-theme.css`（色板 / 圆角 / 阴影），外壳消费 CSS 变量；不复刻原型单屏结构、不引入 Tailwind（D6）。
- [x] 6.5 替换 `App.vue` 的 `HelloWorld` 为路由出口；清理脚手架占位（`HelloWorld.vue` / `Home.vue` 已移除）。
- [x] 6.6 全部 6.x 测试转 Green。

## 7. E2E 与收尾验证

- [x] 7.1 编写 Playwright E2E（真后端栈，经 dev proxy）：用部署注入的初始 Admin "登录 → 进入工作台 → 登出 → 受保护区被拦截回登录"。
- [x] 7.2 运行 `pnpm test:unit` 全绿（29/29）、`pnpm build`（`vue-tsc` 类型检查 + vite 构建）通过。
- [x] 7.3 在 `openspec/changes/frontend-shell/qa/` 用模板补回归影响分析与 QA 测试报告（qa_artifacts 结构检查 PASS）。
- [x] 7.4 `openspec validate frontend-shell --strict` 通过。
