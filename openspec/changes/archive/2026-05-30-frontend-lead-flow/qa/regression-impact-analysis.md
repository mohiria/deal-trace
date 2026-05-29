# Regression Impact Analysis — frontend-lead-flow

## Change Summary

线索旅程 change：新增我的线索列表、公海浏览与认领、线索详情（进度跟踪追加 / 阶段推进 / 赢单 / 流失 / 退回公海 / 闭单只读）。新增 lead API 封装、线索纯函数工具、leads store 与三个视图；路由占位替换为真实视图并新增 `leads/:id`。后端零改动。

## Changed Surfaces

| Surface | Change type | Impact |
| --- | --- | --- |
| `src/api/leads.ts` | 新增 | 线索端点封装；复用既有 `apiClient`/`ApiError`，不改 `client.ts` |
| `src/utils/lead.ts` | 新增 | `isClosed` / 金额校验 / 千分位纯函数 |
| `src/stores/leads.ts` | 新增 | 列表/详情/进度状态 + 认领/退回/进度跨视图联动 |
| `src/views/MyLeadsView.vue` | 新增 | 我的线索（替换占位） |
| `src/views/PublicPoolView.vue` | 新增 | 公海认领（替换占位） |
| `src/views/LeadDetailView.vue` | 新增 | 线索详情 + 写操作 + 闭单只读 |
| `src/router/index.ts` | 修改（占位 → 真实视图 + 新增 `leads/:id`）| 守卫逻辑未改；`my-leads`/`public-pool` 指向真实组件 |
| `src/test/msw/handlers.ts` | 扩展（追加 `/leads/*` 工厂）| 既有 auth 工厂未改 |

## Directly Impacted Existing Behavior

- 无既有业务逻辑被改写。`api/client.ts`（拦截器/`unwrapEnvelope`）、`stores/auth.ts`、`router/guards.ts`、`LoginView`、`AppShell` 均未改动；其 frontend-shell 测试随全量套件 PASS（29 用例）。
- 路由表仅把 `my-leads`/`public-pool` 的占位组件替换为真实视图并新增 `leads/:id`，`beforeEach(authGuard)` 与守卫规则保持不变。

## Backend Impact

无。仅消费已稳定的 `/leads/*` 契约（`/mine`、`/`、`/{id}`、`/pool`、`/{id}/claim`、`/{id}/release`、`/{id}/stage`、`/{id}/win`、`/{id}/lose`、`/{id}/progress`）与既有错误码（`LEAD_ALREADY_CLAIMED`、`LEAD_ENDED_READONLY`、`VALIDATION_ERROR`、`FORBIDDEN`）。不触碰后端代码或 schema。

## Regression Risk

- Level: **Low**
- 理由：后端零改动；本批次为增量新文件，对既有前端仅做路由占位替换；frontend-shell 既有被测点全部随全量套件 PASS。

## Selected Regression Tests

| Test | Why | Result |
| --- | --- | --- |
| frontend-shell 全部 6 文件 / 29 用例 | 守护拦截器/store/守卫/登录/外壳既有语义 | PASS |
| 全量 `pnpm test:unit` | 新增行为与既有互不干扰 | 77/77 PASS |
| `pnpm build`（vue-tsc）| 类型层回归（严格 tsconfig）| PASS（743 模块）|

## Follow-up

- 真后端环境执行 E2E 关键旅程（认领→我的线索→详情→追加进度→赢单只读），需启用 Sales 凭据 + 公海备数据。
- frontend-admin 将在线索详情追加 Admin 分配/回收/转移与系统日志面板：按"区块"追加，不重构本批次既有详情区块。
- frontend-customer 落地客户可搜索选择器后，新建线索可作为后续增量接入"我的线索"。
