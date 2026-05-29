# Regression Impact Analysis — frontend-shell

## Change Summary

前端地基 change：引入登录页、路由守卫、axios 拦截器（token 注入 + 401 会话失效 + 错误归一）、登录态 store、工作台外壳与角色导航、设计 token。后端零改动。

## Changed Surfaces

| Surface | Change type | Impact |
| --- | --- | --- |
| `src/api/client.ts` | 扩展（请求拦截器 + 错误拦截器 + `setUnauthorizedHandler` + `TOKEN_STORAGE_KEY`）| `unwrapEnvelope` 保持纯函数语义不变；所有走 `apiClient` 的请求新增 token 注入与错误归一 |
| `src/stores/auth.ts` | 重写（占位 → 真实登录态）| 仅本 change 新建消费方，无既有依赖 |
| `src/router/index.ts` | 重写（空表 → 路由表 + 守卫）| 首次定义业务路由 |
| `src/App.vue` / `src/main.ts` | 重写（HelloWorld → RouterView + bootstrap）| 启动序列改为先 `restore()` 再 mount |
| `src/style.css` / `styles/arco-theme.css` | 重写（Vite demo 样式 → DealTrace token）| 移除 demo 专用全局样式（`#app{width:1126px;text-align:center}` 等）|
| `vitest.config.ts` | 新增 vue 插件 + setup | 测试基建 |
| `pnpm-workspace.yaml` / `.npmrc` | 构建脚本批准 + 关闭 verifyDepsBeforeRun | 让 `pnpm test:unit` / `build` 可运行 |
| 删除 `HelloWorld.vue` / `Home.vue` | 移除 | 脚手架 demo，无业务引用（已校验无 dangling import）|

## Directly Impacted Existing Behavior

- `unwrapEnvelope`（`api/client.ts`）：唯一既有被测行为。本 change 仅在其外层新增拦截逻辑，未改其签名与抛错语义；`src/api/client.spec.ts` 原 2 用例随全量套件 PASS。

## Backend Impact

无。本 change 仅消费已稳定的 auth-account 契约（`/auth/login`、`/auth/me`、`UNAUTHORIZED`/`FORBIDDEN` 语义），不触碰任何后端代码或 schema。

## Regression Risk

- Level: **Low**
- 理由：后端零改动；前端改动前仅为脚手架（无真实业务行为可回归）；唯一既有被测点 `unwrapEnvelope` 经验证无回归。

## Selected Regression Tests

| Test | Why | Result |
| --- | --- | --- |
| `src/api/client.spec.ts` | 守护 `unwrapEnvelope` 既有语义 | PASS |
| 全量 `pnpm test:unit` | 新增行为互不干扰 | 29/29 PASS |
| `pnpm build`（vue-tsc）| 类型层回归（严格 tsconfig）| PASS |

## Follow-up

- 真后端环境执行 E2E 关键旅程（登录→工作台→登出→拦截）。
- 后续 4 个 frontend change（lead-flow / customer / dashboard / admin）将复用本 change 的拦截器、守卫、MSW 边界与设计 token；地基若调整需回归本 change 测试。
