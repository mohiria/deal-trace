# Lightweight Test Design — frontend-dashboard

## Context

- Requirement / Spec: `openspec/changes/frontend-dashboard/specs/frontend-workbench/spec.md`（5 条 ADDED 需求：首屏只读看板、口径后端裁决、金额精确呈现、流失率空值/零值区分、加载/失败态）
- Change summary: 把工作台首屏 `workbench` 占位路由落成真实只读指标看板，首屏拉取 `GET /api/dashboard` 并渲染今日新增 / 公海待认领 / 本月赢单金额 / 本月流失率四项指标。后端零改动。
- Target modules / APIs / pages: `src/api/dashboard.ts`（新增 `DashboardView` 类型 + `fetchDashboard`）、`src/utils/dashboard.ts`（新增 `formatLossRate` / `formatWonAmount`）、`src/views/DashboardView.vue`（新增）、`src/router/index.ts`（改 `workbench` 路由组件）；消费 `GET /api/dashboard`
- Test environment / constraints: Vitest + @vue/test-utils + MSW（axios 边界，`onUnhandledRequest:'error'`）；E2E 用 Playwright 真后端（缺凭据 `test.skip`）。禁 Tailwind，沿用 Arco + `--dt-*` token。

## Input Sources Checked

- [x] Active Spec / PRD / acceptance criteria / issue
- [x] Existing behavior baseline: tests / code / old Spec / API contract
- [x] Data model / field rules / CRUD matrix
- [x] API contract / auth rules / error shape
- [x] UI states / user roles / user paths
- [x] Code structure / changed code / dependency graph
- [x] Existing tests / historical defects / flaky areas
- [x] Test data / credentials / mocks / CI constraints

## Requirement Authority / Conflict Gate

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Result |
| --- | --- | --- | --- | --- | --- |
| 看板口径（Admin 全局 / Sales 个人）由后端裁决 | dashboard spec（后端权威）+ `DashboardService.load` | frontend-dashboard spec R2 | extends（前端不传视角、不重算口径） | dashboard spec + PRD §7.12 | Proceed |
| 流失率分母为 0 → 渲染 `--` 而非 `0%` | dashboard spec + CLAUDE.md（PRD §7.12） | frontend-dashboard spec R4 | extends（前端据后端 `null` 渲染 `--`） | PRD §7.12 + CLAUDE.md | Proceed |
| 流失率为比率值（0.4=40%），赢单金额空集归 0 | `DashboardService` + `DashboardControllerTest` 断言 | frontend-dashboard spec R3/R4 | extends（前端 ×100 显示百分比，金额 0 显示 ¥0） | 后端实现 + 控制器测试 | Proceed |
| `UNAUTHORIZED` 统一清退 | frontend-workbench 既有需求 + 响应拦截器 | frontend-dashboard spec R5 | reuses（视图不重复处理 401） | frontend-workbench spec | Proceed |

无 `conflicts` 项。后端契约要点已由 `DashboardControllerTest` 固定：`monthlyLossRate`=`0.4`/`0.25`/`0.0`/`null`（数字/空），`monthlyWonAmount`=`150000.50`/`0`（数字）。

## Test Points

| Test point | Source / authority | Design method | Test layer | Input / precondition | Expected result | Assertion target | Priority | Coverage artifact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `fetchDashboard` 命中 `GET /api/dashboard` 且不带任何 query | spec R2 / 后端契约 | 契约测试 | Unit | 调用 `fetchDashboard()` | 请求路径 `/dashboard`、无 query 参数 | 请求 URL + query | P0 | `src/api/dashboard.spec.ts#noViewParam` |
| `fetchDashboard` 返回 unwrap 后的 `DashboardView`（6 字段） | spec R1 / 拦截器 | 契约测试 | Unit | MSW 返回信封 data | 解析为 `DashboardView`，字段与后端同名同型 | 返回对象形状 | P0 | `src/api/dashboard.spec.ts#unwrap` |
| 非鉴权业务错误归一为 `ApiError(code)` 抛出 | spec R5 / 拦截器 | 异常路径 | Unit | MSW 返回 500/业务错误码 | reject 为可分支 ApiError | 错误类型 | P1 | `src/api/dashboard.spec.ts#bizError` |
| `formatLossRate(null)` → `--` | spec R4 / CLAUDE.md | 边界值 | Unit | `null` | `'--'` | 返回串 | P0 | `src/utils/dashboard.spec.ts#lossNull` |
| `formatLossRate(0)` → `0%`（非 `--`） | spec R4 | 边界值 | Unit | `0` | `'0%'` | 返回串 | P0 | `src/utils/dashboard.spec.ts#lossZero` |
| `formatLossRate(0.4)` → `40%` | spec R4 / 后端比率约定 | 等价类 | Unit | `0.4` | `'40%'` | 返回串 | P0 | `src/utils/dashboard.spec.ts#lossRatio` |
| `formatLossRate(0.182)` → `18.2%`（1 位小数） | spec R4 / 原型口径 | 等价类 | Unit | `0.182` | `'18.2%'` | 返回串 | P1 | `src/utils/dashboard.spec.ts#lossOneDecimal` |
| `formatWonAmount(0)` → `¥0`（非空白/`--`） | spec R3 | 边界值 | Unit | `0` | `'¥0'` | 返回串 | P0 | `src/utils/dashboard.spec.ts#amtZero` |
| `formatWonAmount(150000.5)` → 千分位人民币 | spec R3 | 等价类 | Unit | `150000.5` | `'¥150,000.5'`（千分位，不浮点失真） | 返回串 | P0 | `src/utils/dashboard.spec.ts#amtThousands` |
| 首屏挂载即拉取并同屏渲染四项指标 | spec R1 | 场景 | Component | MSW 正常返回 | 四指标文本均可见，值与返回一致 | DOM 文本 | P0 | `DashboardView.spec.ts#renderFour` |
| 加载中呈现加载态、不渲染零值 | spec R5 | 状态 | Component | MSW 延迟返回 | 渲染加载态，指标区无 `0` 误导 | DOM | P0 | `DashboardView.spec.ts#loading` |
| 非鉴权失败呈现可重试失败态、保留登录态 | spec R5 | 异常路径 | Component | MSW 返回 500 | 显示失败 + 重试入口，未渲染零值指标 | DOM + 重试按钮 | P0 | `DashboardView.spec.ts#errorRetry` |
| 点重试重新拉取并成功渲染 | spec R5 | 场景 | Component | 首次 500 → 重试 200 | 重试后渲染四指标 | DOM | P1 | `DashboardView.spec.ts#retrySuccess` |
| 流失率 `null` 渲染 `--`，`0` 渲染 `0%` | spec R4 | 边界值 | Component | 两组 MSW 桩 | 分别显示 `--` / `0%` | DOM 文本 | P0 | `DashboardView.spec.ts#lossRender` |
| 金额 `0` 渲染 `¥0` | spec R3 | 边界值 | Component | MSW `monthlyWonAmount=0` | 显示 `¥0` | DOM 文本 | P1 | `DashboardView.spec.ts#amtZeroRender` |
| 视图不在内部处理 `UNAUTHORIZED`（交拦截器清退） | spec R5 / D4 | 防御性 | Component | —— | 视图代码不捕获 401 分支；401 路径由拦截器既有测试覆盖 | 代码审查 + 既有拦截器测试 | P1 | `client.interceptors.spec.ts`（既有）+ 代码审查 |
| 看板不发起任何写请求 | spec R1 | 防御性 | Component | MSW `onUnhandledRequest:'error'` | 仅 1 个 GET，无写方法请求 | 请求方法 | P1 | `DashboardView.spec.ts#readOnly` |
| 登录后进入首屏可见四指标且值来自后端（Admin） | spec R1/R2 | 场景 | E2E | Admin 登录 → `/` | 四指标卡片可见，数值与后端一致 | 页面可见性 | P0 | `tests/e2e/dashboard.spec.ts#admin` |
| Sales 登录进入首屏可见四指标（个人口径） | spec R2 | 场景 | E2E | Sales 登录 → `/` | 四指标卡片可见 | 页面可见性 | P1 | `tests/e2e/dashboard.spec.ts#sales` |

## Layering Rationale（task 2.2）

- **Unit（纯函数 / API 契约）**：`formatLossRate` / `formatWonAmount` 是看板最易错的边界（`--` vs `0%`、比率 ×100、金额空集归 0），不依赖渲染，用单测穷举；`fetchDashboard` 的"无视角参数 + unwrap 形状"是契约，在 axios 边界用 MSW 验证。
- **Component（MSW）**：三态（loading / error+retry / loaded）、四指标渲染、流失率与金额的呈现分支、只读（无写请求）——这些是视图行为，在组件层用 MSW 打桩各响应，避免起真后端。Arco 弹窗类组件遵循 `:render-to-body="false"` 约定（本视图主要为卡片，无 modal，风险低）。
- **E2E（Playwright 真后端，场景优先）**：只覆盖"登录 → 首屏看到四指标且值来自后端"的关键旅程（Admin / Sales 各一条），不重复单测/组件层已覆盖的分支；缺凭据时 `test.skip`，不强求 Red-Green。

## TDD Candidates

| Test point | Initial failing test | Why it should fail before implementation | Expected Red failure reason | Minimal behavior to pass | Related regression |
| --- | --- | --- | --- | --- | --- |
| `fetchDashboard` 命中 `/dashboard` 无视角参数 + unwrap | `src/api/dashboard.spec.ts` | `./dashboard` 模块未实现 | 导入失败（模块缺失即行为未实现） | 实现 `fetchDashboard` + `DashboardView` | 全量 vitest |
| 流失率 `--`/`0%`/比率、金额 `¥0`/千分位 | `src/utils/dashboard.spec.ts` | 格式化函数未实现 | 导入失败 | 实现 `formatLossRate`/`formatWonAmount` | 全量 vitest |
| 视图四指标 / 三态 / 只读 / 分支 | `src/views/DashboardView.spec.ts` | `DashboardView.vue` 未实现 | 组件缺失 | 实现三态视图 | 全量 vitest |

注：API/utils 为薄封装+纯函数，"模块缺失"是行为未实现的最直接 Red，Green 后因行为通过。

## E2E Scenarios

| Scenario | Persona / role | Preconditions | User path | Critical assertions | Cleanup | Evidence on failure |
| --- | --- | --- | --- | --- | --- | --- |
| Admin 首屏看板 | Admin | 后端运行 + 初始 Admin 凭据 | 登录 → 落 `/` 首屏 | `dashboard-metrics` 可见、四指标标题可见、流失率非空、金额含 `¥` | 无（只读） | trace / screenshot |
| Sales 首屏看板 | Sales | 后端运行 + 启用 Sales 凭据 | 登录 → 落 `/` 首屏 | `dashboard-metrics` 可见、四指标标题可见 | 无（只读） | trace / screenshot |

## Non-TDD Exceptions

| Scope | Reason strict TDD does not apply | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| `tests/e2e/dashboard.spec.ts` | 真后端 E2E 场景优先、需运行环境 | `playwright test --list` 解析通过；待联调运行 | 真实登录→首屏串联未自动执行 |
| 视图"不处理 401" | 防御性断言，路由清退归既有拦截器 | 代码审查 + 既有 `client.interceptors.spec.ts` | 低 |

## Prerequisite Blockers

| Blocker | Affected test point | Required owner action | Status |
| --- | --- | --- | --- |
| 无运行 backend + dev server + `E2E_ADMIN_*`/`E2E_SALES_*` 凭据 | E2E Admin/Sales 旅程 | 联调环境注入凭据后 `pnpm test:e2e` | BLOCKED |

## Coverage Closure

- [x] Each in-scope executable test point has a coverage artifact after prerequisites are available.（单元/组件全覆盖；E2E 待联调）
- [x] New or modified tests were executed and results were recorded.（全量 125 PASS）
- [x] Red tests failed for the expected behavior reason when strict TDD applies.
- [x] Syntax, import, fixture, setup, or environment failures were not counted as valid Red evidence.（薄封装模块缺失 Red 已注明）
- [x] Commands, reports, CI links, logs, screenshots, traces, or responses are recorded as execution evidence when relevant.
- [x] Behavioral evidence describes what assertion proved.
- [x] Coverage evidence maps each covered test point to a project-relative test path and optional `#testName`.
- [x] Uncovered test points and unresolved prerequisite blockers are listed explicitly.
- [x] Requirement conflicts are resolved or explicitly listed as BLOCKED.（无冲突）
- [x] Runtime QA validation, if performed, is treated only as availability smoke evidence and not counted as Unit/API/E2E business coverage.（本批次无需 runtime 验证）

## Notes

- Uncovered test points: 真后端登录→首屏串联（E2E，环境受限）
- Remaining risks: `formatLossRate` 小数位舍入（`maximumFractionDigits:1`，`0.1825`→`18.3%`）边界以单测固定预期，避免回归
- Execution evidence: `npx vitest run` → 18 files / 125 tests PASS；`npx vue-tsc -b` EXIT=0；`npx playwright test --list` 2 tests
- Behavioral evidence: `monthlyLossRate=null`→`--`、`=0`→`0%`、`=0.4`→`40%`；`monthlyWonAmount=0`→`¥0`；看板仅 GET 无写请求
- Coverage evidence: `src/api/dashboard.spec.ts`、`src/utils/dashboard.spec.ts`、`src/views/DashboardView.spec.ts`、`tests/e2e/dashboard.spec.ts`
