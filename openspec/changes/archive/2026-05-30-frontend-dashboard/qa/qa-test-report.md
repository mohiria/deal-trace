# QA Test Report

## Conclusion

- Overall result: PASS（E2E 项 BLOCKED：环境无后端 + 凭据，非失败）
- Requirement / change ID: `frontend-dashboard`（modified capability `frontend-workbench`）
- QA owner: frontend-dashboard change
- Date: 2026-05-30
- Summary: 工作台首屏占位页落成真实只读指标看板。API 契约、格式化边界、视图三态与分支以 TDD（Red→Green）落地，全量 18 文件 / 125 测试通过，类型检查通过。E2E 关键旅程已写、缺真后端凭据按 QA constitution 跳过。

## Evidence Guide

| Evidence type | What to record | Example |
| --- | --- | --- |
| Execution evidence | command / result | `npx vitest run` → 18 files / 125 tests PASS |
| Behavioral evidence | assertion proves | `monthlyLossRate=null` 渲染 `--` 而非 `0%` |
| Coverage evidence | test file#name | `src/utils/dashboard.spec.ts#lossNull` |

## Scope

| Area | In scope? | Notes |
| --- | --- | --- |
| Unit | Yes | API 契约 + 格式化纯函数 |
| API/integration | No | 后端零改动；后端 `DashboardControllerTest` 已覆盖端点契约 |
| E2E | Yes | 关键旅程已写，环境受限 skip |
| Regression | Yes | 见 regression-impact-analysis.md |
| Runtime QA validation | No | 纯前端只读增量，无配置/部署风险 |

## Requirement Authority / Conflict Review

| Behavior | Existing baseline | New requirement source | Relationship | Decision authority | Test action | Code action |
| --- | --- | --- | --- | --- | --- | --- |
| 看板口径按角色裁决 | dashboard spec + `DashboardService` | frontend-dashboard spec R2 | extends | dashboard spec + PRD §7.12 | Add | Implement（前端不传视角/不重算） |
| 流失率分母 0 → `--` | CLAUDE.md / PRD §7.12 | spec R4 | extends | PRD §7.12 | Add | Implement（`null→'--'`） |
| 流失率为比率、金额空集归 0 | `DashboardControllerTest` 断言 | spec R3/R4 | extends | 后端实现 + 控制器测试 | Add | Implement（×100 / `¥0`） |
| `UNAUTHORIZED` 清退 | frontend-workbench + 拦截器 | spec R5 | extends（reuses） | frontend-workbench spec | Keep | 不重复处理 401 |

无 `conflicts` 项。

## TDD Summary

| Test point | Source / authority | Red evidence | Red failure reason | Green evidence | Refactor / regression evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `fetchDashboard` 命中 `/dashboard` 无视角参数 + unwrap | spec R1/R2 | `vitest run src/api/dashboard.spec.ts` 模块缺失失败 | 目标模块 `./dashboard` 未实现 | 同命令 4/4 PASS | 全量 125 PASS | `src/api/dashboard.spec.ts` | PASS |
| 流失率 `--`/`0%`/比率、金额 `¥0`/千分位 | spec R3/R4 + 后端比率约定 | `vitest run src/utils/dashboard.spec.ts` 模块缺失失败 | `formatLossRate`/`formatWonAmount` 未实现 | 同命令 10/10 PASS | 全量 125 PASS | `src/utils/dashboard.spec.ts` | PASS |
| 视图四指标 / 三态 / 分支 / 只读 | spec R1/R3/R4/R5 | `vitest run src/views/DashboardView.spec.ts` 组件缺失失败 | `DashboardView.vue` 未实现 | 同命令 8/8 PASS | 全量 125 PASS | `src/views/DashboardView.spec.ts` | PASS |

注：API / utils 的首次 Red 为"目标模块缺失"——本批次为薄封装 + 纯函数，模块缺失即"行为未实现"的最直接 Red，随后 Green 使其因行为原因通过；视图层 Red 为组件缺失。

## Non-TDD Exceptions

| Scope | Reason strict TDD does not apply | Alternative validation | Residual risk |
| --- | --- | --- | --- |
| `tests/e2e/dashboard.spec.ts` | 真后端 E2E 场景优先、需运行环境 | `playwright test --list` 解析通过；待联调运行 | 真实登录→首屏串联未自动执行 |
| 视图"不处理 401" | 防御性断言，路由清退归拦截器 | 代码审查 + 既有 `client.interceptors.spec.ts` | 低 |

## Tests Run

| Layer | Test / suite | Command | Result | Evidence |
| --- | --- | --- | --- | --- |
| Unit | `src/api/dashboard.spec.ts` | `npx vitest run src/api/dashboard.spec.ts` | PASS | 4/4 |
| Unit | `src/utils/dashboard.spec.ts` | `npx vitest run src/utils/dashboard.spec.ts` | PASS | 10/10 |
| Component | `src/views/DashboardView.spec.ts` | `npx vitest run src/views/DashboardView.spec.ts` | PASS | 8/8 |
| Regression | 全量 frontend | `npx vitest run` | PASS | 18 files / 125 tests |
| Build | 类型检查 | `npx vue-tsc -b` | PASS | EXIT=0 |
| E2E | `tests/e2e/dashboard.spec.ts` | `npx playwright test --list` | PASS（list） | 2 tests 解析；运行态 BLOCKED |

## Tests Not Run / Blockers

| Test / scope | Reason not run | Exact blocker | Required owner action | Residual risk |
| --- | --- | --- | --- | --- |
| `tests/e2e/dashboard.spec.ts` 运行态 | BLOCKED | 无运行 backend + dev server + `E2E_ADMIN_*`/`E2E_SALES_*` 凭据 | 联调环境注入凭据后 `pnpm test:e2e` | 真后端首屏旅程未自动验证；组件层 MSW 已覆盖全部分支 |

## Coverage Summary

| Test point | Layer | Behavioral evidence | Coverage artifact | Status |
| --- | --- | --- | --- | --- |
| 命中 `/dashboard` 无视角参数 | Unit | 请求 query 为空 | `src/api/dashboard.spec.ts` | COVERED |
| null 流失率原样透传 | Unit | `monthlyLossRate` 为 null | `src/api/dashboard.spec.ts` | COVERED |
| 流失率 `--`/`0%`/`40%`/`18.2%` | Unit | 比率 ×100、空值/零值区分 | `src/utils/dashboard.spec.ts` | COVERED |
| 金额 `¥0`/千分位 | Unit | 空集归零、不失真 | `src/utils/dashboard.spec.ts` | COVERED |
| 首屏四指标渲染 | Component | 四标题 + 值可见 | `src/views/DashboardView.spec.ts` | COVERED |
| 仅 GET 无写请求 | Component | 请求方法 `['GET']` | `src/views/DashboardView.spec.ts` | COVERED |
| 加载态不渲染零值 | Component | loading 标记在、metrics 不在 | `src/views/DashboardView.spec.ts` | COVERED |
| 非鉴权失败 + 重试 | Component | error + retry，重试后渲染 | `src/views/DashboardView.spec.ts` | COVERED |
| Admin/Sales 首屏旅程 | E2E | 待联调 | `tests/e2e/dashboard.spec.ts` | BLOCKED |

## Regression Scope

- Changed behavior: 首屏占位 → 真实只读看板（四指标 / 口径裁决 / 流失率与金额呈现 / 三态）
- Directly impacted old behavior: `authGuard` 拦截、`UNAUTHORIZED` 清退、`PlaceholderView` 复用——全量套件全绿
- Historical defects considered: Arco teleport 测试陷阱（本视图无 modal/select，不涉及）
- Requirement-driven test additions / modifications / deletions: 仅新增（4 个 spec 文件），无修改 / 删除既有测试
- Regression risk level: Low
- Selected regression tests and why: 全量 vitest（路由组件替换跨用例）+ `vue-tsc`（类型契约）+ `playwright --list`（config 变更）

## Runtime QA Validation

Runtime QA validation is availability smoke evidence only. It does not count as Unit/API/E2E business coverage.

| Target | Operation | Result | Evidence | Cleanup |
| --- | --- | --- | --- | --- |
| —— | 不需要（纯前端只读增量） | —— | —— | —— |

## Failure Analysis

| Failure / issue | Failure type | Root cause | Action taken | Follow-up coverage |
| --- | --- | --- | --- | --- |
| 无 | —— | —— | —— | —— |

## Failure Learning

- Learning recorded or recommended: No
- Knowledge location: ——
- Summary: 设计期"按 string 接收金额"的假设被 apply 期查证推翻（后端发 JSON 数字），已据真实契约改为 `number` 并留痕于 design Open Questions——属正常 spec→impl 校准，非缺陷。

## Remaining Risks

- Uncovered test points: 真后端登录→首屏串联（E2E 环境受限）
- Unresolved prerequisite blockers: E2E 需联调后端 + 凭据
- Requirement authority conflicts: 无
- Known flaky areas: 无；`Intl.NumberFormat` 用固定 `en-US` locale 规避 ICU 差异
- Manual follow-up: 联调环境运行 E2E

## Final Statement

frontend-dashboard 以 TDD 落地：API 契约、格式化边界、视图三态与呈现分支均先 Red 后 Green，全量 125 测试与类型检查通过，无既有测试被弱化/删除。回归风险低（纯前端只读增量）。唯一未自动执行项为真后端 E2E（缺运行环境与凭据，按 QA constitution 跳过，spec 已写、`--list` 通过），残留风险限于真实端到端串联，待联调验证。
