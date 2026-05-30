# Lightweight Test Design — frontend-admin

## Context

- Requirement / Spec: `openspec/changes/frontend-admin/specs/frontend-workbench/spec.md`（7 条 ADDED 需求）
- Change summary: Admin 用户管理（账号列表、创建 Sales、启用/停用）+ Admin 线索归属调度（分配/回收/转移）+ 隐藏系统日志入口
- Target modules / pages: `src/api/accounts.ts`、`src/api/leads.ts`(追加归属)、`src/stores/accounts.ts`、`src/stores/leads.ts`(追加归属)、`src/views/{UsersView,LeadDetailView}.vue`、`src/components/navigation.ts`、`src/router`
- Test environment / constraints: vitest + jsdom + @vue/test-utils，MSW 在 axios 边界拦截（复用 `src/test/setup.ts` + `src/test/msw/*`）；E2E 用 Playwright 打真后端。Arco `a-modal`/`a-select` 须 `:render-to-body="false"`（项目记忆，否则 teleport 出 wrapper 找不到）。

## Input Sources Checked

- [x] Active Spec / PRD / acceptance criteria（PRD §7.1/§7.6/§7.7、frontend-workbench delta spec）
- [x] Existing behavior baseline: tests / code / API contract（`api/client.ts` 拦截器与 `ApiError`、frontend-lead-flow 的 `isClosed`/`leads` store/详情视图）
- [x] Data model / field rules（`AccountView` 6 字段、`LeadView.ownerSalesId`/`stage`）
- [x] API contract / auth rules / error shape（`/admin/accounts/*`、`/leads/{id}/{assign,recall,transfer}`；`VALIDATION_ERROR`/`LEAD_ENDED_READONLY`/`FORBIDDEN`/`UNAUTHORIZED`）
- [x] UI states / user roles / user paths（ADMIN 专属；归属态 公海/有归属/已结束三态）
- [x] Code structure / changed code / dependency graph（D1 API 层、D2 store、D3 归属态派生、D4 enabledSales、D5 错误分支、D6 自身停用）
- [x] Existing tests / historical defects（不回归 frontend-lead-flow 详情/进度/闭单；不回归 AppShell 导航测试）
- [x] Test data / credentials / mocks / CI constraints（MSW handler 工厂；E2E 用部署注入 Admin）

## Requirement Authority / Conflict Gate

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Result |
| --- | --- | --- | --- | --- | --- |
| 归属操作前置互斥（assign 仅公海/recall·transfer 仅有归属/已结束只读） | 后端 `LeadOwnershipService`（lead-ownership spec） | frontend-workbench spec（分配/回收/转移） | extends（前端按 `ownerSalesId`+`isClosed` 派生入口 + 后端兜底回落） | PRD §7.6 / §7.7 | Proceed |
| 不可停用自己 | 后端 `AdminAccountController`（auth-account spec） | frontend-workbench spec（启用与停用） | extends（前端收起自身停用入口 + 后端 VALIDATION_ERROR 兜底） | PRD §7.1 | Proceed |
| 系统日志查看 | 后端仅写入侧、无读端点 | PRD §7.8 要求详情展示系统日志 | conflicts（本批次无法交付） | 用户确认推迟 | Defer：隐藏入口，不声称交付查看行为 |

`系统日志查看`为已知缺口：本批次不实现查看，仅隐藏导航入口避免死链；spec 未声称交付该行为，无断言削弱。

## Test Points

| Test point | Source / authority | Design method | Test layer | Input / precondition | Expected result | Assertion target | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| accounts API 命中正确方法/路径并 unwrap | design D1 | 等价类 | Unit | 各端点 MSW 成功 | 返回业务负载 | 请求方法/路径+返回值 | P0 | `src/api/accounts.spec.ts` |
| accounts API 错误归一为 ApiError 带 code | design D1/D5 | 等价类 | Unit | MSW 错误信封 | 抛 ApiError(code) | 异常字段 | P0 | `src/api/accounts.spec.ts` |
| assign/recall/transfer 命中正确方法/路径返回 LeadView | design D1 | 等价类 | Unit | MSW 成功 | 返回 LeadView | 请求方法/路径+返回值 | P0 | `src/api/leads.spec.ts` |
| accounts store 加载/创建并入/状态就地更新 | design D2 | 状态迁移 | Unit(store) | MSW 成功 | state 正确联动 | store 状态 | P0 | `src/stores/accounts.spec.ts` |
| `enabledSales` 仅含启用 SALES | design D4 | 决策表 | Unit(store) | 混合账号 | 过滤正确 | computed 值 | P0 | `src/stores/accounts.spec.ts` |
| accounts store 写动作收 ApiError 透传不吞 | design D5 | 等价类 | Unit(store) | MSW 错误 | 抛 ApiError | 抛出 | P0 | `src/stores/accounts.spec.ts` |
| leads store assign/recall/transfer 刷新 currentLead；错误透传 | design D2/D5 | 状态迁移 | Unit(store) | MSW 成功/错误 | currentLead 刷新/抛出 | store 状态 | P0 | `src/stores/leads.spec.ts` |
| 用户管理列表渲染全部账号+无密码字段+按创建时间 | spec（查看账号列表） | 场景 | Unit(组件) | 账号 fixture | 正确渲染 | DOM | P0 | `src/views/UsersView.spec.ts` |
| 创建 Sales 邮箱格式非法/必填空即时拦截不发请求 | spec（创建 Sales） | 边界值 | Unit(组件) | 非法输入 | 拦截 | 无请求 | P0 | `src/views/UsersView.spec.ts` |
| 创建 Sales 成功进入列表；邮箱重复 VALIDATION_ERROR 提示保留表单 | spec（创建 Sales） | 场景 | Unit(组件) | MSW 成功/重复 | 正确反馈 | DOM+无伪造 | P0 | `src/views/UsersView.spec.ts` |
| 停用/启用成功后行 status 更新 | spec（启用与停用） | 状态迁移 | Unit(组件) | MSW 成功 | status 切换 | DOM | P0 | `src/views/UsersView.spec.ts` |
| 自身行不呈现停用入口；停用自身 VALIDATION_ERROR 回落不变 | spec（启用与停用）/ D6 | 决策表 | Unit(组件) | 自身行/MSW 拒绝 | 无入口/回落 | DOM | P0 | `src/views/UsersView.spec.ts` |
| 归属区仅 Admin+未结束呈现；ownerSalesId null→仅分配，非null→回收+转移 | spec（分配/回收/转移）/ D3 | 决策表 | Unit(组件) | role×归属态×闭单 | 入口显隐正确 | DOM | P0 | `src/views/LeadDetailView.spec.ts` |
| 分配/转移候选仅 enabledSales；转移排除当前归属 | spec/ D4 | 决策表 | Unit(组件) | 账号 fixture | 候选正确 | DOM | P0 | `src/views/LeadDetailView.spec.ts` |
| 分配成功更新归属；已有归属 VALIDATION_ERROR 回落刷新 | spec（分配） | 场景 | Unit(组件) | MSW 成功/拒绝 | 正确反馈不伪造 | DOM+刷新 | P0 | `src/views/LeadDetailView.spec.ts` |
| 回收成功进公海；已在公海 VALIDATION_ERROR 回落 | spec（回收） | 场景 | Unit(组件) | MSW 成功/拒绝 | 正确反馈 | DOM+刷新 | P0 | `src/views/LeadDetailView.spec.ts` |
| 转移成功更新归属；目标相同 VALIDATION_ERROR 回落 | spec（转移） | 场景 | Unit(组件) | MSW 成功/拒绝 | 正确反馈 | DOM+刷新 | P0 | `src/views/LeadDetailView.spec.ts` |
| 归属操作遇 LEAD_ENDED_READONLY 提示+刷新只读 | spec（回收/转移）/ D5 | 等价类 | Unit(组件) | MSW 拒绝 | 提示+刷新 | DOM+刷新 | P1 | `src/views/LeadDetailView.spec.ts` |
| 系统日志入口对 ADMIN/SALES 均不呈现 | spec（系统日志入口不呈现） | 决策表 | Unit(纯函数) | role 两态 | 无该入口 | visibleSections 值 | P0 | `src/components/AppShell.spec.ts` |
| /users 守卫：SALES 回落、ADMIN 放行 | 既有 R2/R6 | 决策表 | Unit(守卫) | role 两态 | 回落/放行 | 路由 | P1 | `src/router/guards.spec.ts` |
| Admin 旅程：建 Sales→列表可见→分配线索 | spec 综合 | 场景 | E2E | 真后端 | 端到端通过 | 页面 | P1 | `tests/e2e/admin-flow.spec.ts` |

## TDD Candidates

| Test point | Initial failing test | Why fail before impl | Expected Red reason | Minimal behavior to pass | Related regression |
| --- | --- | --- | --- | --- | --- |
| accounts API 封装 | `accounts.spec.ts` | 模块/函数未实现 | 函数缺失/返回不符 | 实现 `api/accounts.ts` | 不回归 `client.ts` |
| 归属 API 追加 | `leads.spec.ts` | 函数未实现 | 函数缺失 | 追加 `api/leads.ts` | 不回归既有 lead API |
| accounts store | `accounts.spec.ts`(store) | store 未实现 | 态未填充/enabledSales 缺失 | 实现 `stores/accounts.ts` | — |
| leads store 归属动作 | `leads.spec.ts`(store) | action 未实现 | currentLead 未刷新 | 追加 `stores/leads.ts` | 不回归 claim/release |
| UsersView | `UsersView.spec.ts` | 组件不存在 | 渲染/校验/分支断言失败 | 实现 `views/UsersView.vue` | — |
| 详情归属区 | `LeadDetailView.spec.ts`(归属) | 归属区不存在 | 入口/候选/回落断言失败 | 扩展 `views/LeadDetailView.vue` | 不回归详情/进度/闭单 |
| 隐藏系统日志入口 | `AppShell.spec.ts` | 条目仍存在 | `not.toContain('系统日志')` 对 ADMIN 失败 | 从 `navigation.ts` 移除条目 | 不回归用户管理入口可见 |

## Non-TDD Exceptions

| Item | Why not TDD | Alternative verification | Residual risk |
| --- | --- | --- | --- |
| 视觉/样式（`--dt-*` token、布局） | 样式非行为，断言成本高收益低 | 复用既有 token 类名，人工目检 | 低（不影响行为契约） |
| E2E Admin 旅程 | 场景优先，不要求严格 Red-Green | Playwright 真后端跑通 | 中（依赖后端联调环境） |
