# Regression Impact Analysis

## Change Summary

- Requirement / change ID: `frontend-dashboard`（modified capability `frontend-workbench`）
- Change type: code（纯前端增量；后端零改动）
- Changed behavior: 工作台首屏 `workbench` 占位页 → 真实只读指标看板（首屏拉取 `GET /api/dashboard`，渲染今日新增 / 公海待认领 / 本月赢单金额 / 本月流失率四项；口径后端裁决；流失率空值 `--`、零值 `0%`；加载 / 失败可重试态）。
- Impacted modules / APIs / pages: `src/api/dashboard.ts`（新增）、`src/utils/dashboard.ts`（新增）、`src/views/DashboardView.vue`（新增）、`src/router/index.ts`（`workbench` 路由组件替换）、`playwright.config.ts`（新增 `testIdAttribute`）；消费既有 `GET /api/dashboard`
- Author / owner: frontend-dashboard change

## Requirement-Driven Test Changes

| Existing / new test | Action | Requirement source | Reason | Remaining coverage |
| --- | --- | --- | --- | --- |
| `src/api/dashboard.spec.ts` | Add | spec R1/R2/R5 | 契约：命中 `/dashboard`、无视角参数、unwrap 形状、业务错误 ApiError | 4 例覆盖正常 / null 透传 / 错误 |
| `src/utils/dashboard.spec.ts` | Add | spec R3/R4 | 流失率空值/零值/比率、金额空集归零边界 | 10 例穷举边界 |
| `src/views/DashboardView.spec.ts` | Add | spec R1/R3/R4/R5 | 四指标渲染、只读、加载 / 失败 + 重试、流失率与金额分支 | 8 例覆盖三态与分支 |
| `tests/e2e/dashboard.spec.ts` | Add | spec R1/R2 | Admin / Sales 首屏看板关键旅程冒烟 | 2 例，缺凭据 skip |

无既有测试被修改 / 删除 / 弱化。

## Impact Analysis

| Changed item | Impacted behavior | Existing tests to run | New / modified tests needed | Notes |
| --- | --- | --- | --- | --- |
| `router/index.ts`（`workbench` 组件替换） | 首屏 `/` 渲染内容、受保护拦截 | `guards.spec.ts`（既有） | 无需改 | `authGuard` 基于 route meta，未改；拦截逻辑不受组件替换影响 |
| `PlaceholderView` 复用 | contracts / users / system-logs 仍占位 | 既有套件 | 无 | `PlaceholderView` 仍被三处路由引用，未删除 |
| 响应拦截器（`client.ts`） | `UNAUTHORIZED` 清退 | `client.interceptors.spec.ts`（既有） | 无需改 | 看板不重复处理 401，复用既有清退路径 |
| `playwright.config.ts`（`testIdAttribute`） | E2E getByTestId 解析属性 | 既有 4 个 E2E spec | 无 | 既有 E2E 未用 getByTestId，配置变更对其无影响（`--list` 全部正常） |

## Risk Level

- Risk: Low
- Rationale: 纯前端只读增量，不涉及后端 / 迁移 / 金额写入 / 租户边界；唯一跨用例改动（路由组件替换、playwright 配置）经全量单元+组件测试与 `playwright --list` 验证无回归。金额 / 流失率仅展示格式化，数值权威在后端。

## Selected Regression Tests

| Test / suite | Layer | Why selected | Command | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| 全量 frontend 单元+组件 | Unit + Component | 路由组件替换 + 新增视图，确认无回归 | `pnpm test:unit`（`npx vitest run`） | PASS | 18 files / 125 tests passed |
| `dashboard.spec.ts`（API） | Unit | 看板契约 | `npx vitest run src/api/dashboard.spec.ts` | PASS | 4/4 |
| `dashboard.spec.ts`（utils） | Unit | 格式化边界 | `npx vitest run src/utils/dashboard.spec.ts` | PASS | 10/10 |
| `DashboardView.spec.ts` | Component | 三态与分支 | `npx vitest run src/views/DashboardView.spec.ts` | PASS | 8/8 |
| 类型检查 | Build | 类型契约对齐后端 | `npx vue-tsc -b` | PASS | EXIT=0 |
| `tests/e2e/dashboard.spec.ts` | E2E | 关键旅程冒烟 | `pnpm test:e2e` | BLOCKED | 无运行后端 + 凭据，`test.skip`；`--list` 解析通过 |

## Tests Not Run / Blockers

| Test / scope | Reason not run | Exact blocker | Owner action | Residual risk |
| --- | --- | --- | --- | --- |
| `tests/e2e/dashboard.spec.ts` | BLOCKED | 无运行中的 backend（`mvn spring-boot:run`）+ frontend dev server + 初始 Admin/Sales 凭据（`E2E_ADMIN_*` / `E2E_SALES_*`） | 部署联调环境后注入凭据运行 | 真后端首屏旅程未自动验证；组件层已用 MSW 覆盖全部分支，残留风险限于真实登录→路由→渲染串联 |

## Runtime QA Validation

Runtime QA validation is availability smoke evidence only. It does not count as Unit/API/E2E business coverage.

| Needed? | Reason | Operation | Result | Evidence |
| --- | --- | --- | --- | --- |
| No | 纯前端只读增量，无配置 / 部署风险；联调阶段由 E2E 旅程兜底 | —— | —— | —— |

## Regression Conclusion

- Overall result: PASS（E2E 项 BLOCKED，非失败）
- Changed behavior covered: 四指标渲染、口径不重算（无视角参数）、金额空集归零、流失率 `--`/`0%`/百分比、加载/失败+重试、只读（仅 GET）——单元+组件层全覆盖
- Directly impacted old behavior covered: `authGuard` 拦截（`guards.spec.ts`）、`UNAUTHORIZED` 清退（`client.interceptors.spec.ts`）、占位路由复用——全量套件全绿
- Historical defects considered: Arco 组件测试 teleport 约定（本视图为卡片，无 modal/select 下拉，不涉及 `:render-to-body` 陷阱）
- Uncovered test points: 真后端登录→首屏串联（E2E，环境受限）
- Unresolved prerequisite blockers: E2E 需联调后端 + 凭据
- Remaining risks: 低；限于真实端到端串联与 `Intl.NumberFormat` 在不同 ICU/node 下的本地化输出（已用 `en-US` 固定 locale 规避）
